create table if not exists public.laqta_addon_tombstones (
    user_id uuid not null references auth.users(id) on delete cascade,
    profile_index integer not null check (profile_index between 1 and 6),
    addon_url text not null,
    deleted_at timestamptz not null default now(),
    origin_client_id text,
    primary key (user_id, profile_index, addon_url)
);

alter table public.laqta_addon_tombstones enable row level security;
revoke all on public.laqta_addon_tombstones from public, anon, authenticated;

create index if not exists laqta_watch_progress_delete_lookup_idx
    on public.laqta_watch_progress_events (user_id, profile_index, progress_key, last_watched desc)
    where operation = 'delete';
create index if not exists laqta_watched_item_delete_lookup_idx
    on public.laqta_watched_item_events (user_id, profile_index, item_key, watched_at desc)
    where operation = 'delete';
create index if not exists laqta_library_delete_lookup_idx
    on public.laqta_library_events (
        user_id, profile_index, (item_json ->> 'content_id'), (item_json ->> 'content_type')
    )
    where operation = 'delete';

drop function if exists public.sync_push_addons(integer, jsonb, text);

create function public.sync_push_addons(
    p_profile_id integer,
    p_addons jsonb,
    p_origin_client_id text default null,
    p_changed_urls jsonb default null,
    p_deleted_urls jsonb default null
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := auth.uid();
    v_current jsonb;
    v_merged jsonb;
    v_item jsonb;
    v_url text;
begin
    if v_user_id is null then
        raise exception using errcode = '42501', message = 'Authentication required';
    end if;
    if p_profile_id is null or p_profile_id not between 1 and 6 then
        raise exception using errcode = '22023', message = 'Invalid profile';
    end if;
    perform public.laqta_validate_addons(p_addons);
    if (p_changed_urls is not null and jsonb_typeof(p_changed_urls) <> 'array')
       or (p_deleted_urls is not null and jsonb_typeof(p_deleted_urls) <> 'array') then
        raise exception using errcode = '22023', message = 'Invalid addon change list';
    end if;
    if exists (
        select 1
        from jsonb_array_elements(coalesce(p_changed_urls, '[]'::jsonb)) as changed(value)
        where jsonb_typeof(changed.value) <> 'string' or nullif(trim(changed.value #>> '{}'), '') is null
    ) or exists (
        select 1
        from jsonb_array_elements(coalesce(p_deleted_urls, '[]'::jsonb)) as deleted(value)
        where jsonb_typeof(deleted.value) <> 'string' or nullif(trim(deleted.value #>> '{}'), '') is null
    ) then
        raise exception using errcode = '22023', message = 'Invalid addon URL in change list';
    end if;

    perform pg_advisory_xact_lock(hashtextextended(v_user_id::text || ':addons:' || p_profile_id::text, 0));
    select l.addons_json into v_current
    from public.laqta_addon_lists as l
    where l.user_id = v_user_id and l.profile_index = p_profile_id
    for update;
    v_current := coalesce(v_current, '[]'::jsonb);

    if p_changed_urls is null then
        v_merged := p_addons;

        insert into public.laqta_addon_tombstones (
            user_id, profile_index, addon_url, deleted_at, origin_client_id
        )
        select v_user_id, p_profile_id, current_addon.value ->> 'url', now(), p_origin_client_id
        from jsonb_array_elements(v_current) as current_addon(value)
        where not exists (
            select 1
            from jsonb_array_elements(p_addons) as incoming(value)
            where incoming.value ->> 'url' = current_addon.value ->> 'url'
        )
        on conflict (user_id, profile_index, addon_url) do update
        set deleted_at = excluded.deleted_at,
            origin_client_id = excluded.origin_client_id;

        delete from public.laqta_addon_tombstones as tombstone
        where tombstone.user_id = v_user_id
          and tombstone.profile_index = p_profile_id
          and exists (
              select 1 from jsonb_array_elements(p_addons) as incoming(value)
              where incoming.value ->> 'url' = tombstone.addon_url
          );
    else
        v_merged := v_current;

        for v_url in
            select distinct trim(value #>> '{}')
            from jsonb_array_elements(coalesce(p_deleted_urls, '[]'::jsonb))
        loop
            v_merged := coalesce((
                select jsonb_agg(addon.value)
                from jsonb_array_elements(v_merged) as addon(value)
                where addon.value ->> 'url' <> v_url
            ), '[]'::jsonb);

            insert into public.laqta_addon_tombstones (
                user_id, profile_index, addon_url, deleted_at, origin_client_id
            )
            values (v_user_id, p_profile_id, v_url, now(), p_origin_client_id)
            on conflict (user_id, profile_index, addon_url) do update
            set deleted_at = excluded.deleted_at,
                origin_client_id = excluded.origin_client_id;
        end loop;

        for v_url in
            select distinct trim(value #>> '{}')
            from jsonb_array_elements(p_changed_urls)
        loop
            select addon.value into v_item
            from jsonb_array_elements(p_addons) as addon(value)
            where addon.value ->> 'url' = v_url
            limit 1;
            if v_item is null then
                raise exception using errcode = '22023', message = 'Changed addon is missing from payload';
            end if;

            v_merged := coalesce((
                select jsonb_agg(addon.value)
                from jsonb_array_elements(v_merged) as addon(value)
                where addon.value ->> 'url' <> v_url
            ), '[]'::jsonb) || jsonb_build_array(v_item);

            delete from public.laqta_addon_tombstones as tombstone
            where tombstone.user_id = v_user_id
              and tombstone.profile_index = p_profile_id
              and tombstone.addon_url = v_url;
        end loop;

        select coalesce(
            jsonb_agg(addon.value order by (addon.value ->> 'sort_order')::integer, addon.value ->> 'url'),
            '[]'::jsonb
        ) into v_merged
        from jsonb_array_elements(v_merged) as addon(value);
    end if;

    insert into public.laqta_addon_lists (
        user_id, profile_index, addons_json, origin_client_id, updated_at
    )
    values (v_user_id, p_profile_id, v_merged, p_origin_client_id, now())
    on conflict (user_id, profile_index) do update
    set addons_json = excluded.addons_json,
        origin_client_id = excluded.origin_client_id,
        updated_at = excluded.updated_at;
end;
$$;

revoke all on function public.sync_push_addons(integer, jsonb, text, jsonb, jsonb) from public, anon;
grant execute on function public.sync_push_addons(integer, jsonb, text, jsonb, jsonb) to authenticated;

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
        if exists (
            select 1
            from public.laqta_watch_progress_events as event
            where event.user_id = v_user_id
              and event.profile_index = p_profile_id
              and event.progress_key = trim(v_entry.progress_key)
              and event.operation = 'delete'
              and event.last_watched >= greatest(coalesce(v_entry.last_watched, 0), 0)
        ) then
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
        where excluded.last_watched > current_row.last_watched
           or (excluded.last_watched = current_row.last_watched
               and excluded."position" > current_row."position");
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

revoke all on function public.sync_push_watch_progress(integer, jsonb, text) from public, anon;
grant execute on function public.sync_push_watch_progress(integer, jsonb, text) to authenticated;

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

        if exists (
            select 1
            from public.laqta_watched_item_events as event
            where event.user_id = v_user_id
              and event.profile_index = p_profile_id
              and event.item_key = v_item_key
              and event.operation = 'delete'
              and event.watched_at >= greatest(coalesce(v_item.watched_at, 0), 0)
        ) then
            continue;
        end if;

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
        where excluded.watched_at >= current_row.watched_at
          and (current_row.content_id, current_row.content_type, current_row.title,
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

revoke all on function public.sync_push_watched_items(integer, jsonb, text) from public, anon;
grant execute on function public.sync_push_watched_items(integer, jsonb, text) to authenticated;

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
    v_added_at bigint;
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
        v_added_at := greatest(coalesce((v_item ->> 'added_at')::bigint, 0), 0);
        if exists (
            select 1
            from public.laqta_library_events as event
            where event.user_id = v_user_id
              and event.profile_index = p_profile_id
              and event.operation = 'delete'
              and event.item_json ->> 'content_id' = v_id
              and event.item_json ->> 'content_type' = v_type
              and greatest(coalesce((event.item_json ->> 'added_at')::bigint, 0), 0) >= v_added_at
        ) then
            continue;
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

revoke all on function public.sync_push_library_items(integer, jsonb, text) from public, anon;
grant execute on function public.sync_push_library_items(integer, jsonb, text) to authenticated;

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
        delete from public.laqta_library as library_item
        where library_item.user_id = v_user_id
          and library_item.profile_index = p_profile_id
          and library_item.content_id = v_key ->> 'content_id'
          and library_item.content_type = v_key ->> 'content_type'
        returning library_item.item_json into v_item;

        if not found then
            v_item := jsonb_build_object(
                'content_id', trim(v_key ->> 'content_id'),
                'content_type', trim(v_key ->> 'content_type'),
                'added_at', coalesce((
                    select max(greatest(coalesce((event.item_json ->> 'added_at')::bigint, 0), 0))
                    from public.laqta_library_events as event
                    where event.user_id = v_user_id
                      and event.profile_index = p_profile_id
                      and event.item_json ->> 'content_id' = v_key ->> 'content_id'
                      and event.item_json ->> 'content_type' = v_key ->> 'content_type'
                ), 0)
            );
        end if;
        insert into public.laqta_library_events (
            user_id, profile_index, operation, item_json, origin_client_id
        )
        values (v_user_id, p_profile_id, 'delete', v_item, p_origin_client_id);
    end loop;
end;
$$;

revoke all on function public.sync_delete_library_items(integer, jsonb, text) from public, anon;
grant execute on function public.sync_delete_library_items(integer, jsonb, text) to authenticated;

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
    v_deleted boolean;
    v_last_watched bigint;
begin
    if v_user_id is null then
        raise exception using errcode = '42501', message = 'Authentication required';
    end if;
    if p_profile_id not between 1 and 6 or jsonb_typeof(coalesce(p_keys, '[]'::jsonb)) <> 'array' then
        raise exception using errcode = '22023', message = 'Invalid profile or progress keys';
    end if;

    for v_key in select jsonb_array_elements_text(coalesce(p_keys, '[]'::jsonb))
    loop
        v_deleted := false;
        for v_entry in
            delete from public.laqta_watch_progress as progress
            where progress.user_id = v_user_id
              and progress.profile_index = p_profile_id
              and progress.progress_key = v_key
            returning progress.*
        loop
            v_deleted := true;
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

        if not v_deleted then
            select coalesce(max(event.last_watched), 0) into v_last_watched
            from public.laqta_watch_progress_events as event
            where event.user_id = v_user_id
              and event.profile_index = p_profile_id
              and event.progress_key = v_key;
            insert into public.laqta_watch_progress_events (
                user_id, profile_index, operation, progress_key, last_watched,
                origin_client_id
            )
            values (
                v_user_id, p_profile_id, 'delete', v_key, v_last_watched,
                p_origin_client_id
            );
        end if;
    end loop;
end;
$$;

revoke all on function public.sync_delete_watch_progress(integer, jsonb, text) from public, anon;
grant execute on function public.sync_delete_watch_progress(integer, jsonb, text) to authenticated;

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
    v_deleted boolean;
    v_item_key text;
    v_watched_at bigint;
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
            content_type text,
            season integer,
            episode integer
        )
    loop
        if nullif(trim(v_key.content_id), '') is null
           or (v_key.content_type is not null and nullif(trim(v_key.content_type), '') is null) then
            raise exception using errcode = '22023', message = 'Invalid watched-item key';
        end if;
        v_deleted := false;
        for v_item in
            delete from public.laqta_watched_items as watched
            where watched.user_id = v_user_id
              and watched.profile_index = p_profile_id
              and watched.content_id = trim(v_key.content_id)
              and (v_key.content_type is null or watched.content_type = trim(v_key.content_type))
              and watched.season is not distinct from v_key.season
              and watched.episode is not distinct from v_key.episode
            returning watched.*
        loop
            v_deleted := true;
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

        if not v_deleted and v_key.content_type is not null then
            v_item_key := jsonb_build_array(
                trim(v_key.content_id), trim(v_key.content_type), v_key.season, v_key.episode
            )::text;
            select coalesce(max(event.watched_at), 0) into v_watched_at
            from public.laqta_watched_item_events as event
            where event.user_id = v_user_id
              and event.profile_index = p_profile_id
              and event.item_key = v_item_key;
            insert into public.laqta_watched_item_events (
                user_id, profile_index, operation, item_key, content_id,
                content_type, season, episode, watched_at, origin_client_id
            )
            values (
                v_user_id, p_profile_id, 'delete', v_item_key,
                trim(v_key.content_id), v_key.content_type, v_key.season,
                v_key.episode, v_watched_at, p_origin_client_id
            );
        end if;
    end loop;
end;
$$;

revoke all on function public.sync_delete_watched_items(integer, jsonb, text) from public, anon;
grant execute on function public.sync_delete_watched_items(integer, jsonb, text) to authenticated;
