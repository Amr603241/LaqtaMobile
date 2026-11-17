create table if not exists public.laqta_addon_lists (
    user_id uuid not null references auth.users(id) on delete cascade,
    profile_index integer not null check (profile_index between 1 and 6),
    addons_json jsonb not null check (jsonb_typeof(addons_json) = 'array'),
    origin_client_id text,
    updated_at timestamptz not null default now(),
    primary key (user_id, profile_index)
);

alter table public.laqta_addon_lists enable row level security;
revoke all on public.laqta_addon_lists from public, anon, authenticated;

create or replace function public.laqta_validate_addons(p_addons jsonb)
returns void
language plpgsql
immutable
set search_path = ''
as $$
declare
    v_item jsonb;
begin
    if p_addons is null or jsonb_typeof(p_addons) <> 'array' then
        raise exception using errcode = '22023', message = 'Invalid addon list';
    end if;
    for v_item in select value from jsonb_array_elements(p_addons)
    loop
        if jsonb_typeof(v_item) <> 'object'
           or jsonb_typeof(v_item -> 'url') is distinct from 'string'
           or nullif(trim(v_item ->> 'url'), '') is null
           or jsonb_typeof(v_item -> 'name') is distinct from 'string'
           or jsonb_typeof(v_item -> 'enabled') is distinct from 'boolean'
           or jsonb_typeof(v_item -> 'sort_order') is distinct from 'number' then
            raise exception using errcode = '22023', message = 'Invalid addon';
        end if;
        perform (v_item ->> 'sort_order')::integer;
    end loop;
    if (select count(*) <> count(distinct value ->> 'url') from jsonb_array_elements(p_addons)) then
        raise exception using errcode = '22023', message = 'Duplicate addon URL';
    end if;
end;
$$;

create or replace function public.sync_pull_or_seed_addons(
    p_profile_id integer,
    p_addons jsonb,
    p_origin_client_id text default null
)
returns setof jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := auth.uid();
    v_addons jsonb;
begin
    if v_user_id is null then
        raise exception using errcode = '42501', message = 'Authentication required';
    end if;
    if p_profile_id is null or p_profile_id not between 1 and 6 then
        raise exception using errcode = '22023', message = 'Invalid profile';
    end if;
    perform public.laqta_validate_addons(p_addons);
    perform pg_advisory_xact_lock(hashtextextended(v_user_id::text || ':addons:' || p_profile_id::text, 0));
    if jsonb_array_length(p_addons) > 0 then
        insert into public.laqta_addon_lists (user_id, profile_index, addons_json, origin_client_id)
        values (v_user_id, p_profile_id, p_addons, p_origin_client_id)
        on conflict (user_id, profile_index) do nothing;
    end if;
    select l.addons_json into v_addons
    from public.laqta_addon_lists as l
    where l.user_id = v_user_id and l.profile_index = p_profile_id;
    return query select value from jsonb_array_elements(coalesce(v_addons, '[]'::jsonb));
end;
$$;

create or replace function public.sync_push_addons(
    p_profile_id integer,
    p_addons jsonb,
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
    if p_profile_id is null or p_profile_id not between 1 and 6 then
        raise exception using errcode = '22023', message = 'Invalid profile';
    end if;
    perform public.laqta_validate_addons(p_addons);
    perform pg_advisory_xact_lock(hashtextextended(v_user_id::text || ':addons:' || p_profile_id::text, 0));
    insert into public.laqta_addon_lists (user_id, profile_index, addons_json, origin_client_id)
    values (v_user_id, p_profile_id, p_addons, p_origin_client_id)
    on conflict (user_id, profile_index) do update
    set addons_json = excluded.addons_json,
        origin_client_id = excluded.origin_client_id,
        updated_at = now();
end;
$$;

revoke all on function public.laqta_validate_addons(jsonb) from public, anon, authenticated;
revoke all on function public.sync_pull_or_seed_addons(integer, jsonb, text) from public, anon;
revoke all on function public.sync_push_addons(integer, jsonb, text) from public, anon;
grant execute on function public.sync_pull_or_seed_addons(integer, jsonb, text) to authenticated;
grant execute on function public.sync_push_addons(integer, jsonb, text) to authenticated;
