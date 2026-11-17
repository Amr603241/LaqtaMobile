create table if not exists public.laqta_library (
    user_id uuid not null references auth.users(id) on delete cascade,
    profile_index integer not null check (profile_index between 1 and 6),
    content_id text not null,
    content_type text not null,
    item_json jsonb not null,
    primary key (user_id, profile_index, content_id, content_type)
);

create table if not exists public.laqta_library_events (
    event_id bigint generated always as identity primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    profile_index integer not null check (profile_index between 1 and 6),
    operation text not null check (operation in ('upsert', 'delete')),
    item_json jsonb not null,
    origin_client_id text
);

create index if not exists laqta_library_events_cursor_idx
    on public.laqta_library_events (user_id, profile_index, event_id);

alter table public.laqta_library enable row level security;
alter table public.laqta_library_events enable row level security;
revoke all on public.laqta_library from public, anon, authenticated;
revoke all on public.laqta_library_events from public, anon, authenticated;

create or replace function public.sync_get_library_delta_cursor(p_profile_id integer)
returns bigint
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := auth.uid();
    v_cursor bigint;
begin
    if v_user_id is null then
        raise exception using errcode = '42501', message = 'Authentication required';
    end if;
    select coalesce(max(e.event_id), 0) into v_cursor
    from public.laqta_library_events as e
    where e.user_id = v_user_id and e.profile_index = p_profile_id;
    return v_cursor;
end;
$$;

create or replace function public.sync_pull_library(
    p_profile_id integer,
    p_limit integer,
    p_offset integer
)
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := auth.uid();
begin
    if v_user_id is null then
        raise exception using errcode = '42501', message = 'Authentication required';
    end if;
    return query
    select l.item_json
    from public.laqta_library as l
    where l.user_id = v_user_id and l.profile_index = p_profile_id
    order by l.content_id, l.content_type
    limit greatest(1, least(coalesce(p_limit, 200), 1000))
    offset greatest(coalesce(p_offset, 0), 0);
end;
$$;

create or replace function public.sync_pull_library_delta(
    p_profile_id integer,
    p_since_event_id bigint,
    p_limit integer
)
returns setof jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := auth.uid();
begin
    if v_user_id is null then
        raise exception using errcode = '42501', message = 'Authentication required';
    end if;
    return query
    select e.item_json || jsonb_build_object('event_id', e.event_id, 'operation', e.operation)
    from public.laqta_library_events as e
    where e.user_id = v_user_id
      and e.profile_index = p_profile_id
      and e.event_id > greatest(coalesce(p_since_event_id, 0), 0)
    order by e.event_id
    limit greatest(1, least(coalesce(p_limit, 200), 1000));
end;
$$;

create or replace function public.sync_push_library_items(
    p_profile_id integer,
    p_items jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := auth.uid();
    v_item jsonb;
    v_id text;
    v_type text;
    v_changed integer;
begin
    if v_user_id is null then
        raise exception using errcode = '42501', message = 'Authentication required';
    end if;
    if p_profile_id is null or p_profile_id not between 1 and 6
       or p_items is null or jsonb_typeof(p_items) <> 'array' then
        raise exception using errcode = '22023', message = 'Invalid profile or library items';
    end if;
    perform pg_advisory_xact_lock(hashtextextended(v_user_id::text || ':library:' || p_profile_id::text, 0));
    for v_item in select value from jsonb_array_elements(p_items)
    loop
        v_id := v_item ->> 'content_id';
        v_type := v_item ->> 'content_type';
        if jsonb_typeof(v_item) <> 'object'
           or jsonb_typeof(v_item -> 'content_id') is distinct from 'string'
           or jsonb_typeof(v_item -> 'content_type') is distinct from 'string'
           or nullif(trim(v_id), '') is null or nullif(trim(v_type), '') is null then
            raise exception using errcode = '22023', message = 'Invalid library item';
        end if;
        insert into public.laqta_library as existing (
            user_id, profile_index, content_id, content_type, item_json
        )
        values (v_user_id, p_profile_id, v_id, v_type, v_item)
        on conflict (user_id, profile_index, content_id, content_type) do update
        set item_json = excluded.item_json
        where existing.item_json is distinct from excluded.item_json;
        get diagnostics v_changed = row_count;
        if v_changed > 0 then
            insert into public.laqta_library_events (
                user_id, profile_index, operation, item_json, origin_client_id
            )
            values (v_user_id, p_profile_id, 'upsert', v_item, p_origin_client_id);
        end if;
    end loop;
end;
$$;

create or replace function public.sync_delete_library_items(
    p_profile_id integer,
    p_keys jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := auth.uid();
    v_key jsonb;
    v_item jsonb;
begin
    if v_user_id is null then
        raise exception using errcode = '42501', message = 'Authentication required';
    end if;
    if p_profile_id is null or p_profile_id not between 1 and 6
       or p_keys is null or jsonb_typeof(p_keys) <> 'array' then
        raise exception using errcode = '22023', message = 'Invalid profile or library keys';
    end if;
    perform pg_advisory_xact_lock(hashtextextended(v_user_id::text || ':library:' || p_profile_id::text, 0));
    for v_key in select value from jsonb_array_elements(p_keys)
    loop
        if jsonb_typeof(v_key) <> 'object'
           or jsonb_typeof(v_key -> 'content_id') is distinct from 'string'
           or jsonb_typeof(v_key -> 'content_type') is distinct from 'string'
           or nullif(trim(v_key ->> 'content_id'), '') is null
           or nullif(trim(v_key ->> 'content_type'), '') is null then
            raise exception using errcode = '22023', message = 'Invalid library key';
        end if;
        delete from public.laqta_library as l
        where l.user_id = v_user_id
          and l.profile_index = p_profile_id
          and l.content_id = v_key ->> 'content_id'
          and l.content_type = v_key ->> 'content_type'
        returning l.item_json into v_item;
        if found then
            insert into public.laqta_library_events (
                user_id, profile_index, operation, item_json, origin_client_id
            )
            values (v_user_id, p_profile_id, 'delete', v_item, p_origin_client_id);
        end if;
    end loop;
end;
$$;

revoke all on function public.sync_get_library_delta_cursor(integer) from public, anon;
revoke all on function public.sync_pull_library(integer, integer, integer) from public, anon;
revoke all on function public.sync_pull_library_delta(integer, bigint, integer) from public, anon;
revoke all on function public.sync_push_library_items(integer, jsonb, text) from public, anon;
revoke all on function public.sync_delete_library_items(integer, jsonb, text) from public, anon;
grant execute on function public.sync_get_library_delta_cursor(integer) to authenticated;
grant execute on function public.sync_pull_library(integer, integer, integer) to authenticated;
grant execute on function public.sync_pull_library_delta(integer, bigint, integer) to authenticated;
grant execute on function public.sync_push_library_items(integer, jsonb, text) to authenticated;
grant execute on function public.sync_delete_library_items(integer, jsonb, text) to authenticated;
