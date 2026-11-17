create table if not exists public.laqta_profiles (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    profile_index integer not null check (profile_index between 1 and 6),
    name text not null,
    avatar_color_hex text not null default '#1E88E5',
    avatar_id text,
    avatar_url text,
    profile_background_id text,
    profile_background_url text,
    uses_primary_addons boolean not null default false,
    uses_primary_plugins boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (user_id, profile_index)
);

create table if not exists public.laqta_profile_settings (
    user_id uuid not null references auth.users(id) on delete cascade,
    profile_index integer not null check (profile_index between 1 and 6),
    platform text not null,
    settings_json jsonb not null default '{}'::jsonb,
    origin_client_id text,
    updated_at timestamptz not null default now(),
    primary key (user_id, profile_index, platform)
);

create table if not exists public.laqta_watch_progress (
    user_id uuid not null references auth.users(id) on delete cascade,
    profile_index integer not null check (profile_index between 1 and 6),
    progress_key text not null,
    content_id text not null,
    content_type text not null,
    video_id text not null,
    season integer,
    episode integer,
    "position" bigint not null default 0 check ("position" >= 0),
    duration bigint not null default 0 check (duration >= 0),
    last_watched bigint not null default 0 check (last_watched >= 0),
    origin_client_id text,
    updated_at timestamptz not null default now(),
    primary key (user_id, profile_index, progress_key)
);

create table if not exists public.laqta_watch_progress_events (
    event_id bigint generated always as identity primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    profile_index integer not null check (profile_index between 1 and 6),
    operation text not null check (operation in ('upsert', 'delete')),
    progress_key text not null,
    content_id text not null default '',
    content_type text not null default '',
    video_id text not null default '',
    season integer,
    episode integer,
    "position" bigint not null default 0,
    duration bigint not null default 0,
    last_watched bigint not null default 0,
    origin_client_id text,
    created_at timestamptz not null default now()
);

create table if not exists public.laqta_watched_items (
    user_id uuid not null references auth.users(id) on delete cascade,
    profile_index integer not null check (profile_index between 1 and 6),
    item_key text not null,
    content_id text not null,
    content_type text not null,
    title text not null default '',
    season integer,
    episode integer,
    watched_at bigint not null default 0 check (watched_at >= 0),
    origin_client_id text,
    updated_at timestamptz not null default now(),
    primary key (user_id, profile_index, item_key)
);

create table if not exists public.laqta_watched_item_events (
    event_id bigint generated always as identity primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    profile_index integer not null check (profile_index between 1 and 6),
    operation text not null check (operation in ('upsert', 'delete')),
    item_key text not null,
    content_id text not null default '',
    content_type text not null default '',
    title text not null default '',
    season integer,
    episode integer,
    watched_at bigint not null default 0,
    origin_client_id text,
    created_at timestamptz not null default now()
);

create index if not exists laqta_watch_progress_events_cursor_idx
    on public.laqta_watch_progress_events (user_id, profile_index, event_id);
create index if not exists laqta_watched_item_events_cursor_idx
    on public.laqta_watched_item_events (user_id, profile_index, event_id);
create index if not exists laqta_watched_items_order_idx
    on public.laqta_watched_items (user_id, profile_index, watched_at desc);

alter table public.laqta_profiles enable row level security;
alter table public.laqta_profile_settings enable row level security;
alter table public.laqta_watch_progress enable row level security;
alter table public.laqta_watch_progress_events enable row level security;
alter table public.laqta_watched_items enable row level security;
alter table public.laqta_watched_item_events enable row level security;

drop policy if exists laqta_profiles_owner on public.laqta_profiles;
create policy laqta_profiles_owner on public.laqta_profiles
    for all to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));

drop policy if exists laqta_profile_settings_owner on public.laqta_profile_settings;
create policy laqta_profile_settings_owner on public.laqta_profile_settings
    for all to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));

drop policy if exists laqta_watch_progress_owner on public.laqta_watch_progress;
create policy laqta_watch_progress_owner on public.laqta_watch_progress
    for all to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));

drop policy if exists laqta_watch_progress_events_owner on public.laqta_watch_progress_events;
create policy laqta_watch_progress_events_owner on public.laqta_watch_progress_events
    for all to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));

drop policy if exists laqta_watched_items_owner on public.laqta_watched_items;
create policy laqta_watched_items_owner on public.laqta_watched_items
    for all to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));

drop policy if exists laqta_watched_item_events_owner on public.laqta_watched_item_events;
create policy laqta_watched_item_events_owner on public.laqta_watched_item_events
    for all to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));

revoke all on public.laqta_profiles from anon, authenticated;
revoke all on public.laqta_profile_settings from anon, authenticated;
revoke all on public.laqta_watch_progress from anon, authenticated;
revoke all on public.laqta_watch_progress_events from anon, authenticated;
revoke all on public.laqta_watched_items from anon, authenticated;
revoke all on public.laqta_watched_item_events from anon, authenticated;

create or replace function public.sync_pull_profiles()
returns table (
    id uuid,
    user_id uuid,
    profile_index integer,
    name text,
    avatar_color_hex text,
    avatar_id text,
    avatar_url text,
    profile_background_id text,
    profile_background_url text,
    uses_primary_addons boolean,
    uses_primary_plugins boolean,
    pin_enabled boolean,
    pin_locked_until timestamptz,
    created_at timestamptz,
    updated_at timestamptz
)
language plpgsql
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
    select p.id, p.user_id, p.profile_index, p.name, p.avatar_color_hex,
           p.avatar_id, p.avatar_url, p.profile_background_id,
           p.profile_background_url, p.uses_primary_addons,
           p.uses_primary_plugins, false, null::timestamptz,
           p.created_at, p.updated_at
    from public.laqta_profiles as p
    where p.user_id = v_user_id
    order by p.profile_index;
end;
$$;

create or replace function public.sync_push_profiles(
    p_client_max_profiles integer,
    p_profiles jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := auth.uid();
    v_max_profiles integer := least(greatest(coalesce(p_client_max_profiles, 1), 1), 6);
begin
    if v_user_id is null then
        raise exception using errcode = '42501', message = 'Authentication required';
    end if;
    if jsonb_typeof(coalesce(p_profiles, '[]'::jsonb)) <> 'array' then
        raise exception using errcode = '22023', message = 'Profiles must be a JSON array';
    end if;
    if jsonb_array_length(coalesce(p_profiles, '[]'::jsonb)) > v_max_profiles then
        raise exception using errcode = '22023', message = 'Profile limit exceeded';
    end if;

    insert into public.laqta_profiles (
        user_id, profile_index, name, avatar_color_hex, avatar_id, avatar_url,
        profile_background_id, profile_background_url,
        uses_primary_addons, uses_primary_plugins, updated_at
    )
    select v_user_id, p.profile_index, left(coalesce(p.name, ''), 80),
           coalesce(nullif(p.avatar_color_hex, ''), '#1E88E5'),
           nullif(p.avatar_id, ''), nullif(p.avatar_url, ''),
           nullif(p.profile_background_id, ''), nullif(p.profile_background_url, ''),
           coalesce(p.uses_primary_addons, false), coalesce(p.uses_primary_plugins, false),
           now()
    from jsonb_to_recordset(coalesce(p_profiles, '[]'::jsonb)) as p(
        profile_index integer,
        name text,
        avatar_color_hex text,
        avatar_id text,
        avatar_url text,
        profile_background_id text,
        profile_background_url text,
        uses_primary_addons boolean,
        uses_primary_plugins boolean
    )
    where p.profile_index between 1 and v_max_profiles
    on conflict (user_id, profile_index) do update
    set name = excluded.name,
        avatar_color_hex = excluded.avatar_color_hex,
        avatar_id = excluded.avatar_id,
        avatar_url = excluded.avatar_url,
        profile_background_id = excluded.profile_background_id,
        profile_background_url = excluded.profile_background_url,
        uses_primary_addons = excluded.uses_primary_addons,
        uses_primary_plugins = excluded.uses_primary_plugins,
        updated_at = now();
end;
$$;

create or replace function public.sync_pull_profile_settings_blob(
    p_profile_id integer,
    p_platform text
)
returns table (
    profile_id integer,
    settings_json jsonb,
    updated_at timestamptz
)
language plpgsql
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
    select s.profile_index, s.settings_json, s.updated_at
    from public.laqta_profile_settings as s
    where s.user_id = v_user_id
      and s.profile_index = p_profile_id
      and s.platform = p_platform;
end;
$$;

create or replace function public.sync_push_profile_settings_blob(
    p_profile_id integer,
    p_platform text,
    p_settings_json jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := auth.uid();
begin
    if v_user_id is null then
        raise exception using errcode = '42501', message = 'Authentication required';
    end if;
    if p_profile_id not between 1 and 6 or nullif(trim(p_platform), '') is null then
        raise exception using errcode = '22023', message = 'Invalid profile or platform';
    end if;
    if p_settings_json is null or jsonb_typeof(p_settings_json) <> 'object' then
        raise exception using errcode = '22023', message = 'Settings must be a JSON object';
    end if;

    insert into public.laqta_profile_settings (
        user_id, profile_index, platform, settings_json, origin_client_id, updated_at
    )
    values (
        v_user_id, p_profile_id, trim(p_platform), p_settings_json,
        p_origin_client_id, now()
    )
    on conflict (user_id, profile_index, platform) do update
    set settings_json = excluded.settings_json,
        origin_client_id = excluded.origin_client_id,
        updated_at = now();
end;
$$;

create or replace function public.sync_get_watch_progress_delta_cursor(p_profile_id integer)
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
    from public.laqta_watch_progress_events as e
    where e.user_id = v_user_id and e.profile_index = p_profile_id;
    return v_cursor;
end;
$$;

create or replace function public.sync_pull_watch_progress_delta(
    p_profile_id integer,
    p_since_event_id bigint,
    p_limit integer
)
returns table (
    event_id bigint,
    operation text,
    progress_key text,
    content_id text,
    content_type text,
    video_id text,
    season integer,
    episode integer,
    "position" bigint,
    duration bigint,
    last_watched bigint
)
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
    select e.event_id, e.operation, e.progress_key, e.content_id,
           e.content_type, e.video_id, e.season, e.episode, e."position",
           e.duration, e.last_watched
    from public.laqta_watch_progress_events as e
    where e.user_id = v_user_id
      and e.profile_index = p_profile_id
      and e.event_id > greatest(coalesce(p_since_event_id, 0), 0)
    order by e.event_id
    limit greatest(1, least(coalesce(p_limit, 900), 1000));
end;
$$;

create or replace function public.sync_pull_watch_progress(
    p_profile_id integer,
    p_since_last_watched bigint default null,
    p_limit integer default null
)
returns table (
    progress_key text,
    content_id text,
    content_type text,
    video_id text,
    season integer,
    episode integer,
    "position" bigint,
    duration bigint,
    last_watched bigint
)
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
    select w.progress_key, w.content_id, w.content_type, w.video_id,
           w.season, w.episode, w."position", w.duration, w.last_watched
    from public.laqta_watch_progress as w
    where w.user_id = v_user_id
      and w.profile_index = p_profile_id
      and (p_since_last_watched is null or w.last_watched > p_since_last_watched)
    order by w.last_watched desc, w.progress_key
    limit greatest(1, least(coalesce(p_limit, 5000), 5000));
end;
$$;

create or replace function public.sync_push_watch_progress(
    p_profile_id integer,
    p_entries jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := auth.uid();
    v_entry record;
    v_changed integer;
begin
    if v_user_id is null then
        raise exception using errcode = '42501', message = 'Authentication required';
    end if;
    if p_profile_id not between 1 and 6 or jsonb_typeof(coalesce(p_entries, '[]'::jsonb)) <> 'array' then
        raise exception using errcode = '22023', message = 'Invalid profile or progress payload';
    end if;

    for v_entry in
        select x.*
        from jsonb_to_recordset(coalesce(p_entries, '[]'::jsonb)) as x(
            progress_key text,
            content_id text,
            content_type text,
            video_id text,
            season integer,
            episode integer,
            "position" bigint,
            duration bigint,
            last_watched bigint
        )
    loop
        if nullif(trim(v_entry.progress_key), '') is null
           or nullif(trim(v_entry.content_id), '') is null
           or nullif(trim(v_entry.content_type), '') is null
           or nullif(trim(v_entry.video_id), '') is null then
            continue;
        end if;

        insert into public.laqta_watch_progress as current_row (
            user_id, profile_index, progress_key, content_id, content_type,
            video_id, season, episode, "position", duration, last_watched,
            origin_client_id, updated_at
        )
        values (
            v_user_id, p_profile_id, trim(v_entry.progress_key),
            trim(v_entry.content_id), trim(v_entry.content_type),
            trim(v_entry.video_id), v_entry.season, v_entry.episode,
            greatest(coalesce(v_entry."position", 0), 0),
            greatest(coalesce(v_entry.duration, 0), 0),
            greatest(coalesce(v_entry.last_watched, 0), 0),
            p_origin_client_id, now()
        )
        on conflict (user_id, profile_index, progress_key) do update
        set content_id = excluded.content_id,
            content_type = excluded.content_type,
            video_id = excluded.video_id,
            season = excluded.season,
            episode = excluded.episode,
            "position" = excluded."position",
            duration = excluded.duration,
            last_watched = excluded.last_watched,
            origin_client_id = excluded.origin_client_id,
            updated_at = now()
        where (current_row.content_id, current_row.content_type, current_row.video_id,
               current_row.season, current_row.episode, current_row."position",
               current_row.duration, current_row.last_watched)
              is distinct from
              (excluded.content_id, excluded.content_type, excluded.video_id,
               excluded.season, excluded.episode, excluded."position",
               excluded.duration, excluded.last_watched);
        get diagnostics v_changed = row_count;

        if v_changed > 0 then
            insert into public.laqta_watch_progress_events (
                user_id, profile_index, operation, progress_key, content_id,
                content_type, video_id, season, episode, "position", duration,
                last_watched, origin_client_id
            )
            select w.user_id, w.profile_index, 'upsert', w.progress_key,
                   w.content_id, w.content_type, w.video_id, w.season,
                   w.episode, w."position", w.duration, w.last_watched,
                   p_origin_client_id
            from public.laqta_watch_progress as w
            where w.user_id = v_user_id
              and w.profile_index = p_profile_id
              and w.progress_key = trim(v_entry.progress_key);
        end if;
    end loop;
end;
$$;

create or replace function public.sync_delete_watch_progress(
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
    v_key text;
    v_entry record;
begin
    if v_user_id is null then
        raise exception using errcode = '42501', message = 'Authentication required';
    end if;
    if p_profile_id not between 1 and 6 or jsonb_typeof(coalesce(p_keys, '[]'::jsonb)) <> 'array' then
        raise exception using errcode = '22023', message = 'Invalid profile or progress keys';
    end if;

    for v_key in select jsonb_array_elements_text(coalesce(p_keys, '[]'::jsonb))
    loop
        for v_entry in
            delete from public.laqta_watch_progress as w
            where w.user_id = v_user_id
              and w.profile_index = p_profile_id
              and w.progress_key = v_key
            returning w.*
        loop
            insert into public.laqta_watch_progress_events (
                user_id, profile_index, operation, progress_key, content_id,
                content_type, video_id, season, episode, "position", duration,
                last_watched, origin_client_id
            )
            values (
                v_entry.user_id, v_entry.profile_index, 'delete', v_entry.progress_key,
                v_entry.content_id, v_entry.content_type, v_entry.video_id,
                v_entry.season, v_entry.episode, v_entry."position", v_entry.duration,
                v_entry.last_watched, p_origin_client_id
            );
        end loop;
    end loop;
end;
$$;

create or replace function public.sync_get_watched_items_delta_cursor(p_profile_id integer)
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
    from public.laqta_watched_item_events as e
    where e.user_id = v_user_id and e.profile_index = p_profile_id;
    return v_cursor;
end;
$$;

create or replace function public.sync_pull_watched_items_delta(
    p_profile_id integer,
    p_since_event_id bigint,
    p_limit integer
)
returns table (
    event_id bigint,
    operation text,
    content_id text,
    content_type text,
    title text,
    season integer,
    episode integer,
    watched_at bigint
)
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
    select e.event_id, e.operation, e.content_id, e.content_type,
           e.title, e.season, e.episode, e.watched_at
    from public.laqta_watched_item_events as e
    where e.user_id = v_user_id
      and e.profile_index = p_profile_id
      and e.event_id > greatest(coalesce(p_since_event_id, 0), 0)
    order by e.event_id
    limit greatest(1, least(coalesce(p_limit, 900), 1000));
end;
$$;

create or replace function public.sync_pull_watched_items(
    p_profile_id integer,
    p_page integer,
    p_page_size integer
)
returns table (
    content_id text,
    content_type text,
    title text,
    season integer,
    episode integer,
    watched_at bigint
)
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
    select w.content_id, w.content_type, w.title, w.season, w.episode, w.watched_at
    from public.laqta_watched_items as w
    where w.user_id = v_user_id and w.profile_index = p_profile_id
    order by w.watched_at desc, w.item_key
    limit greatest(1, least(coalesce(p_page_size, 200), 1000))
    offset greatest(coalesce(p_page, 1) - 1, 0) * greatest(1, least(coalesce(p_page_size, 200), 1000));
end;
$$;

create or replace function public.sync_push_watched_items(
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
    v_item record;
    v_item_key text;
    v_changed integer;
begin
    if v_user_id is null then
        raise exception using errcode = '42501', message = 'Authentication required';
    end if;
    if p_profile_id not between 1 and 6 or jsonb_typeof(coalesce(p_items, '[]'::jsonb)) <> 'array' then
        raise exception using errcode = '22023', message = 'Invalid profile or watched-items payload';
    end if;

    for v_item in
        select x.*
        from jsonb_to_recordset(coalesce(p_items, '[]'::jsonb)) as x(
            content_id text,
            content_type text,
            title text,
            season integer,
            episode integer,
            watched_at bigint
        )
    loop
        if nullif(trim(v_item.content_id), '') is null
           or nullif(trim(v_item.content_type), '') is null then
            continue;
        end if;
        v_item_key := jsonb_build_array(
            trim(v_item.content_id), trim(v_item.content_type), v_item.season, v_item.episode
        )::text;

        insert into public.laqta_watched_items as current_row (
            user_id, profile_index, item_key, content_id, content_type, title,
            season, episode, watched_at, origin_client_id, updated_at
        )
        values (
            v_user_id, p_profile_id, v_item_key, trim(v_item.content_id),
            trim(v_item.content_type), left(coalesce(v_item.title, ''), 300),
            v_item.season, v_item.episode, greatest(coalesce(v_item.watched_at, 0), 0),
            p_origin_client_id, now()
        )
        on conflict (user_id, profile_index, item_key) do update
        set content_id = excluded.content_id,
            content_type = excluded.content_type,
            title = excluded.title,
            season = excluded.season,
            episode = excluded.episode,
            watched_at = excluded.watched_at,
            origin_client_id = excluded.origin_client_id,
            updated_at = now()
        where (current_row.content_id, current_row.content_type, current_row.title,
               current_row.season, current_row.episode, current_row.watched_at)
              is distinct from
              (excluded.content_id, excluded.content_type, excluded.title,
               excluded.season, excluded.episode, excluded.watched_at);
        get diagnostics v_changed = row_count;

        if v_changed > 0 then
            insert into public.laqta_watched_item_events (
                user_id, profile_index, operation, item_key, content_id,
                content_type, title, season, episode, watched_at, origin_client_id
            )
            select w.user_id, w.profile_index, 'upsert', w.item_key, w.content_id,
                   w.content_type, w.title, w.season, w.episode, w.watched_at,
                   p_origin_client_id
            from public.laqta_watched_items as w
            where w.user_id = v_user_id
              and w.profile_index = p_profile_id
              and w.item_key = v_item_key;
        end if;
    end loop;
end;
$$;

create or replace function public.sync_delete_watched_items(
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
    v_key record;
    v_item record;
begin
    if v_user_id is null then
        raise exception using errcode = '42501', message = 'Authentication required';
    end if;
    if p_profile_id not between 1 and 6 or jsonb_typeof(coalesce(p_keys, '[]'::jsonb)) <> 'array' then
        raise exception using errcode = '22023', message = 'Invalid profile or watched-item keys';
    end if;

    for v_key in
        select x.*
        from jsonb_to_recordset(coalesce(p_keys, '[]'::jsonb)) as x(
            content_id text,
            season integer,
            episode integer
        )
    loop
        for v_item in
            delete from public.laqta_watched_items as w
            where w.user_id = v_user_id
              and w.profile_index = p_profile_id
              and w.content_id = v_key.content_id
              and w.season is not distinct from v_key.season
              and w.episode is not distinct from v_key.episode
            returning w.*
        loop
            insert into public.laqta_watched_item_events (
                user_id, profile_index, operation, item_key, content_id,
                content_type, title, season, episode, watched_at, origin_client_id
            )
            values (
                v_item.user_id, v_item.profile_index, 'delete', v_item.item_key,
                v_item.content_id, v_item.content_type, v_item.title,
                v_item.season, v_item.episode, v_item.watched_at, p_origin_client_id
            );
        end loop;
    end loop;
end;
$$;

revoke all on function public.sync_pull_profiles() from public, anon;
revoke all on function public.sync_push_profiles(integer, jsonb, text) from public, anon;
revoke all on function public.sync_pull_profile_settings_blob(integer, text) from public, anon;
revoke all on function public.sync_push_profile_settings_blob(integer, text, jsonb, text) from public, anon;
revoke all on function public.sync_get_watch_progress_delta_cursor(integer) from public, anon;
revoke all on function public.sync_pull_watch_progress_delta(integer, bigint, integer) from public, anon;
revoke all on function public.sync_pull_watch_progress(integer, bigint, integer) from public, anon;
revoke all on function public.sync_push_watch_progress(integer, jsonb, text) from public, anon;
revoke all on function public.sync_delete_watch_progress(integer, jsonb, text) from public, anon;
revoke all on function public.sync_get_watched_items_delta_cursor(integer) from public, anon;
revoke all on function public.sync_pull_watched_items_delta(integer, bigint, integer) from public, anon;
revoke all on function public.sync_pull_watched_items(integer, integer, integer) from public, anon;
revoke all on function public.sync_push_watched_items(integer, jsonb, text) from public, anon;
revoke all on function public.sync_delete_watched_items(integer, jsonb, text) from public, anon;

grant execute on function public.sync_pull_profiles() to authenticated;
grant execute on function public.sync_push_profiles(integer, jsonb, text) to authenticated;
grant execute on function public.sync_pull_profile_settings_blob(integer, text) to authenticated;
grant execute on function public.sync_push_profile_settings_blob(integer, text, jsonb, text) to authenticated;
grant execute on function public.sync_get_watch_progress_delta_cursor(integer) to authenticated;
grant execute on function public.sync_pull_watch_progress_delta(integer, bigint, integer) to authenticated;
grant execute on function public.sync_pull_watch_progress(integer, bigint, integer) to authenticated;
grant execute on function public.sync_push_watch_progress(integer, jsonb, text) to authenticated;
grant execute on function public.sync_delete_watch_progress(integer, jsonb, text) to authenticated;
grant execute on function public.sync_get_watched_items_delta_cursor(integer) to authenticated;
grant execute on function public.sync_pull_watched_items_delta(integer, bigint, integer) to authenticated;
grant execute on function public.sync_pull_watched_items(integer, integer, integer) to authenticated;
grant execute on function public.sync_push_watched_items(integer, jsonb, text) to authenticated;
grant execute on function public.sync_delete_watched_items(integer, jsonb, text) to authenticated;
