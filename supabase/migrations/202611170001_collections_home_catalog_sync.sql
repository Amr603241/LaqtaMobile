create table if not exists public.laqta_collections (
    user_id uuid not null references auth.users(id) on delete cascade,
    profile_index integer not null check (profile_index between 1 and 6),
    collections_json jsonb not null,
    origin_client_id text,
    updated_at timestamptz not null default now(),
    primary key (user_id, profile_index)
);

create table if not exists public.laqta_home_catalog_settings (
    user_id uuid not null references auth.users(id) on delete cascade,
    profile_index integer not null check (profile_index between 1 and 6),
    platform text not null,
    settings_json jsonb not null,
    origin_client_id text,
    updated_at timestamptz not null default now(),
    primary key (user_id, profile_index, platform)
);

alter table public.laqta_collections enable row level security;
alter table public.laqta_home_catalog_settings enable row level security;

revoke all on public.laqta_collections from public, anon, authenticated;
revoke all on public.laqta_home_catalog_settings from public, anon, authenticated;

create or replace function public.sync_pull_collections(p_profile_id integer)
returns table (profile_id integer, collections_json jsonb, updated_at timestamptz)
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
    select c.profile_index, c.collections_json, c.updated_at
    from public.laqta_collections as c
    where c.user_id = v_user_id and c.profile_index = p_profile_id;
end;
$$;

create or replace function public.sync_push_collections(
    p_profile_id integer,
    p_collections_json jsonb,
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
    if p_profile_id is null or p_profile_id not between 1 and 6
       or p_collections_json is null or jsonb_typeof(p_collections_json) <> 'array' then
        raise exception using errcode = '22023', message = 'Invalid profile or collections';
    end if;
    insert into public.laqta_collections (
        user_id, profile_index, collections_json, origin_client_id, updated_at
    )
    values (v_user_id, p_profile_id, p_collections_json, p_origin_client_id, now())
    on conflict (user_id, profile_index) do update
    set collections_json = excluded.collections_json,
        origin_client_id = excluded.origin_client_id,
        updated_at = now();
end;
$$;

create or replace function public.sync_pull_home_catalog_settings(
    p_profile_id integer,
    p_platform text
)
returns table (profile_id integer, settings_json jsonb)
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
    select c.profile_index, c.settings_json
    from public.laqta_home_catalog_settings as c
    where c.user_id = v_user_id
      and c.profile_index = p_profile_id
      and c.platform = p_platform;
end;
$$;

create or replace function public.sync_push_home_catalog_settings(
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
    if p_profile_id is null or p_profile_id not between 1 and 6
       or nullif(trim(p_platform), '') is null
       or p_settings_json is null or jsonb_typeof(p_settings_json) <> 'object' then
        raise exception using errcode = '22023', message = 'Invalid profile or catalog settings';
    end if;
    insert into public.laqta_home_catalog_settings (
        user_id, profile_index, platform, settings_json, origin_client_id, updated_at
    )
    values (
        v_user_id, p_profile_id, trim(p_platform), p_settings_json, p_origin_client_id, now()
    )
    on conflict (user_id, profile_index, platform) do update
    set settings_json = excluded.settings_json,
        origin_client_id = excluded.origin_client_id,
        updated_at = now();
end;
$$;

revoke all on function public.sync_pull_collections(integer) from public, anon;
revoke all on function public.sync_push_collections(integer, jsonb, text) from public, anon;
revoke all on function public.sync_pull_home_catalog_settings(integer, text) from public, anon;
revoke all on function public.sync_push_home_catalog_settings(integer, text, jsonb, text) from public, anon;

grant execute on function public.sync_pull_collections(integer) to authenticated;
grant execute on function public.sync_push_collections(integer, jsonb, text) to authenticated;
grant execute on function public.sync_pull_home_catalog_settings(integer, text) to authenticated;
grant execute on function public.sync_push_home_catalog_settings(integer, text, jsonb, text) to authenticated;
