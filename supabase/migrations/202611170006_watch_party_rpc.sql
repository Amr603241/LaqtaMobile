create table if not exists public.laqta_watch_party_messages (
    id bigint generated always as identity primary key,
    party_id uuid not null references public.laqta_watch_parties(id) on delete cascade,
    member_id uuid not null,
    name text not null default '',
    avatar_url text,
    avatar_color text,
    body text not null check (length(body) between 1 and 500),
    client_id text check (client_id is null or length(client_id) between 1 and 64),
    created_at timestamptz not null default now()
);

create unique index if not exists laqta_watch_party_messages_client_idx
    on public.laqta_watch_party_messages (party_id, member_id, client_id)
    where client_id is not null;

create index if not exists laqta_watch_party_messages_party_idx
    on public.laqta_watch_party_messages (party_id, id);

alter table public.laqta_watch_party_messages enable row level security;
revoke all on public.laqta_watch_party_messages from public, anon, authenticated;

create or replace function public.laqta_party_now_ms()
returns bigint
language sql
volatile
set search_path = ''
as $$
    select floor(extract(epoch from clock_timestamp()) * 1000)::bigint
$$;

create or replace function public.laqta_party_require_user()
returns uuid
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_user uuid := auth.uid();
begin
    if v_user is null or coalesce((auth.jwt() ->> 'is_anonymous')::boolean, false) then
        raise exception 'sign_in_required' using errcode = '28000';
    end if;
    return v_user;
end;
$$;

create or replace function public.laqta_party_name(p_name text)
returns text
language sql
immutable
set search_path = ''
as $$
    select coalesce(nullif(left(btrim(coalesce(p_name, '')), 40), ''), 'Guest')
$$;

create or replace function public.laqta_party_broadcast(p_party_id uuid, p_event text, p_payload jsonb)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    perform realtime.send(p_payload, p_event, 'watch-party:' || p_party_id::text, true);
exception when others then
    null;
end;
$$;

create or replace function public.laqta_party_snapshot(p_party_id uuid, p_member_id uuid)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
    select jsonb_build_object(
        'party', jsonb_build_object(
            'id', p.id,
            'code', p.code,
            'host_member_id', p.host_member_id,
            'host_only', p.host_only,
            'media', p.media,
            'playback', p.playback,
            'max_members', p.max_members
        ),
        'members', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id', m.id,
                'display_name', m.display_name,
                'avatar_url', m.avatar_url,
                'avatar_color', m.avatar_color
            ) order by m.joined_at)
            from public.laqta_watch_party_members as m
            where m.party_id = p.id and m.removed_at is null
        ), '[]'::jsonb),
        'member_id', p_member_id,
        'server_time_ms', public.laqta_party_now_ms()
    )
    from public.laqta_watch_parties as p
    where p.id = p_party_id
$$;

create or replace function public.laqta_party_drop_member(p_party_id uuid, p_member_id uuid, p_reason text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_party public.laqta_watch_parties%rowtype;
    v_next uuid;
begin
    select * into v_party from public.laqta_watch_parties where id = p_party_id for update;
    if not found or v_party.ended_at is not null then
        return;
    end if;

    if v_party.host_member_id = p_member_id then
        select m.id into v_next
        from public.laqta_watch_party_members as m
        where m.party_id = p_party_id and m.id <> p_member_id and m.removed_at is null
        order by m.joined_at
        limit 1;

        if v_next is null then
            update public.laqta_watch_parties set ended_at = now() where id = p_party_id;
            perform public.laqta_party_broadcast(p_party_id, 'ended', jsonb_build_object('reason', 'empty'));
            return;
        end if;

        update public.laqta_watch_parties set host_member_id = v_next where id = p_party_id;
        v_party.host_member_id := v_next;
    end if;

    if p_reason = 'removed' then
        update public.laqta_watch_party_members
        set removed_at = now()
        where id = p_member_id and party_id = p_party_id and removed_at is null;
    else
        delete from public.laqta_watch_party_members where id = p_member_id and party_id = p_party_id;
    end if;

    perform public.laqta_party_broadcast(p_party_id, 'member_left', jsonb_build_object(
        'member_id', p_member_id,
        'reason', p_reason,
        'host_member_id', v_party.host_member_id
    ));
end;
$$;

create or replace function public.laqta_party_prune(p_party_id uuid, p_keep uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_member record;
begin
    for v_member in
        select m.id
        from public.laqta_watch_party_members as m
        where m.party_id = p_party_id
          and m.removed_at is null
          and m.last_seen_at < now() - interval '10 minutes'
          and m.id is distinct from p_keep
        order by m.joined_at
    loop
        perform public.laqta_party_drop_member(p_party_id, v_member.id, 'stale');
    end loop;
end;
$$;

create or replace function public.laqta_party_resolve(p_party_id uuid, p_device_id text, p_user uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_party public.laqta_watch_parties%rowtype;
    v_member public.laqta_watch_party_members%rowtype;
begin
    select * into v_party from public.laqta_watch_parties where id = p_party_id;
    if not found then
        return jsonb_build_object('error', 'party_not_found');
    end if;
    if v_party.ended_at is not null then
        return jsonb_build_object('error', 'party_ended');
    end if;
    select * into v_member
    from public.laqta_watch_party_members
    where party_id = p_party_id and user_id = p_user and device_id = p_device_id;
    if not found then
        return jsonb_build_object('error', 'not_member');
    end if;
    if v_member.removed_at is not null then
        return jsonb_build_object('error', 'removed');
    end if;
    return jsonb_build_object('member_id', v_member.id);
end;
$$;

create or replace function public.watch_party_clock()
returns bigint
language sql
volatile
security definer
set search_path = ''
as $$
    select public.laqta_party_now_ms()
$$;

create or replace function public.watch_party_enabled()
returns boolean
language sql
stable
set search_path = ''
as $$
    select true
$$;

create or replace function public.watch_party_create(
    p_device_id text,
    p_display_name text,
    p_media jsonb,
    p_avatar_url text default null,
    p_avatar_color text default null,
    p_playback jsonb default null,
    p_profile_index integer default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := public.laqta_party_require_user();
    v_alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    v_code text;
    v_party_id uuid := gen_random_uuid();
    v_member_id uuid := gen_random_uuid();
    v_previous record;
    v_attempt integer := 0;
begin
    if p_device_id is null or length(p_device_id) not between 1 and 128 then
        return jsonb_build_object('error', 'unavailable');
    end if;
    if p_media is null or jsonb_typeof(p_media) <> 'object' then
        return jsonb_build_object('error', 'unavailable');
    end if;
    if p_playback is not null and jsonb_typeof(p_playback) <> 'object' then
        p_playback := null;
    end if;

    delete from public.laqta_watch_parties
    where ended_at is not null and ended_at < now() - interval '1 day';

    for v_previous in
        select m.party_id, m.id
        from public.laqta_watch_party_members as m
        join public.laqta_watch_parties as p on p.id = m.party_id
        where m.user_id = v_user and m.device_id = p_device_id and m.removed_at is null and p.ended_at is null
    loop
        perform public.laqta_party_drop_member(v_previous.party_id, v_previous.id, 'left');
    end loop;

    loop
        v_attempt := v_attempt + 1;
        v_code := (
            select string_agg(substr(v_alphabet, 1 + floor(random() * length(v_alphabet))::integer, 1), '')
            from generate_series(1, 6)
        );
        exit when not exists (select 1 from public.laqta_watch_parties where code = v_code);
        if v_attempt >= 20 then
            return jsonb_build_object('error', 'unavailable');
        end if;
    end loop;

    insert into public.laqta_watch_parties (id, code, owner_user_id, host_member_id, media, playback)
    values (v_party_id, v_code, v_user, v_member_id, p_media, p_playback);

    insert into public.laqta_watch_party_members (
        id, party_id, user_id, device_id, profile_index, display_name, avatar_url, avatar_color
    )
    values (
        v_member_id, v_party_id, v_user, p_device_id, p_profile_index,
        public.laqta_party_name(p_display_name), p_avatar_url, p_avatar_color
    );

    return public.laqta_party_snapshot(v_party_id, v_member_id);
end;
$$;

create or replace function public.watch_party_join(
    p_code text,
    p_device_id text,
    p_display_name text,
    p_avatar_url text default null,
    p_avatar_color text default null,
    p_profile_index integer default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := public.laqta_party_require_user();
    v_party public.laqta_watch_parties%rowtype;
    v_member public.laqta_watch_party_members%rowtype;
    v_count integer;
begin
    if p_device_id is null or length(p_device_id) not between 1 and 128 then
        return jsonb_build_object('error', 'unavailable');
    end if;

    select * into v_party
    from public.laqta_watch_parties
    where code = upper(btrim(coalesce(p_code, '')))
    for update;
    if not found then
        return jsonb_build_object('error', 'party_not_found');
    end if;
    if v_party.ended_at is not null then
        return jsonb_build_object('error', 'party_ended');
    end if;

    perform public.laqta_party_prune(v_party.id, null);

    select * into v_party from public.laqta_watch_parties where id = v_party.id;
    if v_party.ended_at is not null then
        return jsonb_build_object('error', 'party_ended');
    end if;

    select * into v_member
    from public.laqta_watch_party_members
    where party_id = v_party.id and user_id = v_user and device_id = p_device_id;

    if found then
        if v_member.removed_at is not null then
            return jsonb_build_object('error', 'removed');
        end if;
        update public.laqta_watch_party_members
        set last_seen_at = now(),
            display_name = public.laqta_party_name(p_display_name),
            avatar_url = p_avatar_url,
            avatar_color = p_avatar_color,
            profile_index = coalesce(p_profile_index, profile_index)
        where id = v_member.id;
        return public.laqta_party_snapshot(v_party.id, v_member.id);
    end if;

    select count(*) into v_count
    from public.laqta_watch_party_members
    where party_id = v_party.id and removed_at is null;
    if v_count >= v_party.max_members then
        return jsonb_build_object('error', 'party_full');
    end if;

    insert into public.laqta_watch_party_members (
        party_id, user_id, device_id, profile_index, display_name, avatar_url, avatar_color
    )
    values (
        v_party.id, v_user, p_device_id, p_profile_index,
        public.laqta_party_name(p_display_name), p_avatar_url, p_avatar_color
    )
    returning * into v_member;

    perform public.laqta_party_broadcast(v_party.id, 'member_joined', jsonb_build_object('member_id', v_member.id));
    return public.laqta_party_snapshot(v_party.id, v_member.id);
end;
$$;

create or replace function public.watch_party_link_profile(
    p_device_id text,
    p_profile_index integer,
    p_member_id uuid default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := public.laqta_party_require_user();
    v_member public.laqta_watch_party_members%rowtype;
    v_profile public.laqta_profiles%rowtype;
begin
    select m.* into v_member
    from public.laqta_watch_party_members as m
    join public.laqta_watch_parties as p on p.id = m.party_id
    where m.user_id = v_user
      and m.device_id = p_device_id
      and m.removed_at is null
      and p.ended_at is null
      and (p_member_id is null or m.id = p_member_id)
    order by m.joined_at desc
    limit 1;
    if not found then
        return jsonb_build_object('error', 'not_member');
    end if;

    select * into v_profile
    from public.laqta_profiles
    where user_id = v_user and profile_index = p_profile_index;

    update public.laqta_watch_party_members
    set profile_index = p_profile_index,
        last_seen_at = now(),
        display_name = case when v_profile.id is null then display_name else public.laqta_party_name(v_profile.name) end,
        avatar_url = case when v_profile.id is null then avatar_url else v_profile.avatar_url end,
        avatar_color = case when v_profile.id is null then avatar_color else v_profile.avatar_color_hex end
    where id = v_member.id;

    return public.laqta_party_snapshot(v_member.party_id, v_member.id);
end;
$$;

create or replace function public.watch_party_preview(p_code text)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_party public.laqta_watch_parties%rowtype;
    v_host public.laqta_watch_party_members%rowtype;
    v_count integer;
begin
    perform public.laqta_party_require_user();
    select * into v_party
    from public.laqta_watch_parties
    where code = upper(btrim(coalesce(p_code, ''))) and ended_at is null;
    if not found then
        return jsonb_build_object('error', 'party_not_found');
    end if;

    select * into v_host from public.laqta_watch_party_members where id = v_party.host_member_id;
    select count(*) into v_count
    from public.laqta_watch_party_members
    where party_id = v_party.id and removed_at is null;

    return jsonb_build_object(
        'media', v_party.media - 'source',
        'host', jsonb_build_object(
            'id', v_host.id,
            'display_name', v_host.display_name,
            'avatar_url', v_host.avatar_url,
            'avatar_color', v_host.avatar_color
        ),
        'members', v_count,
        'max_members', v_party.max_members
    );
end;
$$;

create or replace function public.watch_party_heartbeat(p_party_id uuid, p_device_id text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := public.laqta_party_require_user();
    v_resolved jsonb := public.laqta_party_resolve(p_party_id, p_device_id, v_user);
    v_member_id uuid;
begin
    if v_resolved ? 'error' then
        return v_resolved;
    end if;
    v_member_id := (v_resolved ->> 'member_id')::uuid;
    update public.laqta_watch_party_members set last_seen_at = now() where id = v_member_id;
    perform public.laqta_party_prune(p_party_id, v_member_id);
    return public.laqta_party_snapshot(p_party_id, v_member_id);
end;
$$;

create or replace function public.watch_party_update(
    p_party_id uuid,
    p_device_id text,
    p_media jsonb default null,
    p_playback jsonb default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := public.laqta_party_require_user();
    v_resolved jsonb := public.laqta_party_resolve(p_party_id, p_device_id, v_user);
    v_member_id uuid;
    v_party public.laqta_watch_parties%rowtype;
begin
    if v_resolved ? 'error' then
        return v_resolved;
    end if;
    v_member_id := (v_resolved ->> 'member_id')::uuid;

    select * into v_party from public.laqta_watch_parties where id = p_party_id for update;
    if v_party.host_only and v_party.host_member_id <> v_member_id then
        return jsonb_build_object('error', 'host_only');
    end if;

    update public.laqta_watch_party_members set last_seen_at = now() where id = v_member_id;

    if p_media is not null and jsonb_typeof(p_media) = 'object' then
        update public.laqta_watch_parties
        set media = p_media,
            playback = case when p_playback is not null and jsonb_typeof(p_playback) = 'object' then p_playback else null end
        where id = p_party_id;
        perform public.laqta_party_broadcast(p_party_id, 'media', jsonb_build_object(
            'media', p_media,
            'playback', p_playback
        ));
    elsif p_playback is not null and jsonb_typeof(p_playback) = 'object' then
        if v_party.playback is null
            or v_party.playback ->> 'media_key' is distinct from p_playback ->> 'media_key'
            or coalesce((p_playback ->> 'seq')::bigint, 0) >= coalesce((v_party.playback ->> 'seq')::bigint, 0) then
            update public.laqta_watch_parties set playback = p_playback where id = p_party_id;
        end if;
    end if;

    return public.laqta_party_snapshot(p_party_id, v_member_id);
end;
$$;

create or replace function public.watch_party_leave(p_party_id uuid, p_device_id text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := public.laqta_party_require_user();
    v_member_id uuid;
begin
    select m.id into v_member_id
    from public.laqta_watch_party_members as m
    where m.party_id = p_party_id and m.user_id = v_user and m.device_id = p_device_id and m.removed_at is null;
    if v_member_id is not null then
        perform public.laqta_party_drop_member(p_party_id, v_member_id, 'left');
    end if;
    return jsonb_build_object('ok', true);
end;
$$;

create or replace function public.watch_party_end(p_party_id uuid, p_device_id text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := public.laqta_party_require_user();
    v_resolved jsonb := public.laqta_party_resolve(p_party_id, p_device_id, v_user);
    v_member_id uuid;
    v_party public.laqta_watch_parties%rowtype;
begin
    if v_resolved ? 'error' then
        return v_resolved;
    end if;
    v_member_id := (v_resolved ->> 'member_id')::uuid;
    select * into v_party from public.laqta_watch_parties where id = p_party_id for update;
    if v_party.host_member_id <> v_member_id then
        return jsonb_build_object('error', 'not_host');
    end if;
    update public.laqta_watch_parties set ended_at = now() where id = p_party_id;
    perform public.laqta_party_broadcast(p_party_id, 'ended', jsonb_build_object('reason', 'host'));
    return jsonb_build_object('ok', true);
end;
$$;

create or replace function public.watch_party_set_host_only(p_party_id uuid, p_device_id text, p_host_only boolean)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := public.laqta_party_require_user();
    v_resolved jsonb := public.laqta_party_resolve(p_party_id, p_device_id, v_user);
    v_member_id uuid;
    v_party public.laqta_watch_parties%rowtype;
begin
    if v_resolved ? 'error' then
        return v_resolved;
    end if;
    v_member_id := (v_resolved ->> 'member_id')::uuid;
    select * into v_party from public.laqta_watch_parties where id = p_party_id for update;
    if v_party.host_member_id <> v_member_id then
        return jsonb_build_object('error', 'not_host');
    end if;
    update public.laqta_watch_parties set host_only = coalesce(p_host_only, false) where id = p_party_id;
    perform public.laqta_party_broadcast(p_party_id, 'control', jsonb_build_object('host_only', coalesce(p_host_only, false)));
    return public.laqta_party_snapshot(p_party_id, v_member_id);
end;
$$;

create or replace function public.watch_party_remove_member(p_party_id uuid, p_device_id text, p_member_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := public.laqta_party_require_user();
    v_resolved jsonb := public.laqta_party_resolve(p_party_id, p_device_id, v_user);
    v_member_id uuid;
    v_party public.laqta_watch_parties%rowtype;
begin
    if v_resolved ? 'error' then
        return v_resolved;
    end if;
    v_member_id := (v_resolved ->> 'member_id')::uuid;
    select * into v_party from public.laqta_watch_parties where id = p_party_id for update;
    if v_party.host_member_id <> v_member_id then
        return jsonb_build_object('error', 'not_host');
    end if;
    if p_member_id = v_member_id then
        return jsonb_build_object('error', 'not_host');
    end if;
    perform public.laqta_party_drop_member(p_party_id, p_member_id, 'removed');
    return public.laqta_party_snapshot(p_party_id, v_member_id);
end;
$$;

create or replace function public.laqta_party_chat_json(p_message public.laqta_watch_party_messages, p_member_id uuid)
returns jsonb
language sql
stable
set search_path = ''
as $$
    select jsonb_build_object(
        'id', p_message.id,
        'member_id', p_message.member_id,
        'name', p_message.name,
        'avatar_url', p_message.avatar_url,
        'avatar_color', p_message.avatar_color,
        'body', p_message.body,
        'client_id', p_message.client_id,
        'sent_at', floor(extract(epoch from p_message.created_at) * 1000)::bigint,
        'mine', p_message.member_id = p_member_id
    )
$$;

create or replace function public.watch_party_send_message(
    p_party_id uuid,
    p_device_id text,
    p_body text,
    p_client_id text default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user uuid := public.laqta_party_require_user();
    v_resolved jsonb := public.laqta_party_resolve(p_party_id, p_device_id, v_user);
    v_member_id uuid;
    v_member public.laqta_watch_party_members%rowtype;
    v_message public.laqta_watch_party_messages%rowtype;
    v_body text := left(btrim(coalesce(p_body, '')), 500);
    v_client text := left(nullif(btrim(coalesce(p_client_id, '')), ''), 64);
begin
    if v_resolved ? 'error' then
        return v_resolved;
    end if;
    if v_body = '' then
        return jsonb_build_object('error', 'unavailable');
    end if;
    v_member_id := (v_resolved ->> 'member_id')::uuid;

    if v_client is not null then
        select * into v_message
        from public.laqta_watch_party_messages
        where party_id = p_party_id and member_id = v_member_id and client_id = v_client;
        if found then
            return jsonb_build_object('message', public.laqta_party_chat_json(v_message, v_member_id));
        end if;
    end if;

    if (
        select count(*) from public.laqta_watch_party_messages
        where party_id = p_party_id and member_id = v_member_id and created_at > now() - interval '10 seconds'
    ) >= 15 then
        return jsonb_build_object('error', 'rate_limited');
    end if;

    select * into v_member from public.laqta_watch_party_members where id = v_member_id;
    update public.laqta_watch_party_members set last_seen_at = now() where id = v_member_id;

    insert into public.laqta_watch_party_messages (party_id, member_id, name, avatar_url, avatar_color, body, client_id)
    values (p_party_id, v_member_id, v_member.display_name, v_member.avatar_url, v_member.avatar_color, v_body, v_client)
    returning * into v_message;

    perform public.laqta_party_broadcast(
        p_party_id,
        'chat',
        public.laqta_party_chat_json(v_message, null::uuid)
    );
    return jsonb_build_object('message', public.laqta_party_chat_json(v_message, v_member_id));
end;
$$;

create or replace function public.watch_party_list_messages(
    p_party_id uuid,
    p_device_id text,
    p_after bigint default null
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_user uuid := public.laqta_party_require_user();
    v_resolved jsonb := public.laqta_party_resolve(p_party_id, p_device_id, v_user);
    v_member_id uuid;
begin
    if v_resolved ? 'error' then
        return v_resolved;
    end if;
    v_member_id := (v_resolved ->> 'member_id')::uuid;
    return jsonb_build_object('messages', coalesce((
        select jsonb_agg(public.laqta_party_chat_json(t.c, v_member_id) order by (t.c).id)
        from (
            select c
            from public.laqta_watch_party_messages as c
            where c.party_id = p_party_id and (p_after is null or c.id > p_after)
            order by c.id desc
            limit 100
        ) as t
    ), '[]'::jsonb));
end;
$$;

create or replace function public.laqta_watch_party_topic_allowed(p_topic text)
returns boolean
language sql
stable
set search_path = ''
as $$
    select coalesce(
        p_topic ~ '^watch-party:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
        and public.laqta_watch_party_is_member(substr(p_topic, 13)::uuid),
        false
    )
$$;

do $$
begin
    if to_regclass('realtime.messages') is not null then
        drop policy if exists laqta_watch_party_receive on realtime.messages;
        create policy laqta_watch_party_receive on realtime.messages
            for select to authenticated
            using (public.laqta_watch_party_topic_allowed((select realtime.topic())));

        drop policy if exists laqta_watch_party_send on realtime.messages;
        create policy laqta_watch_party_send on realtime.messages
            for insert to authenticated
            with check (public.laqta_watch_party_topic_allowed((select realtime.topic())));
    end if;
end;
$$;

revoke all on function public.laqta_party_now_ms() from public, anon, authenticated;
revoke all on function public.laqta_party_require_user() from public, anon, authenticated;
revoke all on function public.laqta_party_name(text) from public, anon, authenticated;
revoke all on function public.laqta_party_broadcast(uuid, text, jsonb) from public, anon, authenticated;
revoke all on function public.laqta_party_snapshot(uuid, uuid) from public, anon, authenticated;
revoke all on function public.laqta_party_drop_member(uuid, uuid, text) from public, anon, authenticated;
revoke all on function public.laqta_party_prune(uuid, uuid) from public, anon, authenticated;
revoke all on function public.laqta_party_resolve(uuid, text, uuid) from public, anon, authenticated;
revoke all on function public.laqta_party_chat_json(public.laqta_watch_party_messages, uuid) from public, anon, authenticated;
grant execute on function public.laqta_watch_party_topic_allowed(text) to authenticated;

revoke all on function public.watch_party_clock() from public;
revoke all on function public.watch_party_enabled() from public;
grant execute on function public.watch_party_clock() to anon, authenticated;
grant execute on function public.watch_party_enabled() to anon, authenticated;

revoke all on function public.watch_party_create(text, text, jsonb, text, text, jsonb, integer) from public, anon;
revoke all on function public.watch_party_join(text, text, text, text, text, integer) from public, anon;
revoke all on function public.watch_party_link_profile(text, integer, uuid) from public, anon;
revoke all on function public.watch_party_preview(text) from public, anon;
revoke all on function public.watch_party_heartbeat(uuid, text) from public, anon;
revoke all on function public.watch_party_update(uuid, text, jsonb, jsonb) from public, anon;
revoke all on function public.watch_party_leave(uuid, text) from public, anon;
revoke all on function public.watch_party_end(uuid, text) from public, anon;
revoke all on function public.watch_party_set_host_only(uuid, text, boolean) from public, anon;
revoke all on function public.watch_party_remove_member(uuid, text, uuid) from public, anon;
revoke all on function public.watch_party_send_message(uuid, text, text, text) from public, anon;
revoke all on function public.watch_party_list_messages(uuid, text, bigint) from public, anon;

grant execute on function public.watch_party_create(text, text, jsonb, text, text, jsonb, integer) to authenticated;
grant execute on function public.watch_party_join(text, text, text, text, text, integer) to authenticated;
grant execute on function public.watch_party_link_profile(text, integer, uuid) to authenticated;
grant execute on function public.watch_party_preview(text) to authenticated;
grant execute on function public.watch_party_heartbeat(uuid, text) to authenticated;
grant execute on function public.watch_party_update(uuid, text, jsonb, jsonb) to authenticated;
grant execute on function public.watch_party_leave(uuid, text) to authenticated;
grant execute on function public.watch_party_end(uuid, text) to authenticated;
grant execute on function public.watch_party_set_host_only(uuid, text, boolean) to authenticated;
grant execute on function public.watch_party_remove_member(uuid, text, uuid) to authenticated;
grant execute on function public.watch_party_send_message(uuid, text, text, text) to authenticated;
grant execute on function public.watch_party_list_messages(uuid, text, bigint) to authenticated;
