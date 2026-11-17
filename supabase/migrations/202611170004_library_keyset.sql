create or replace function public.sync_pull_library_keyset(
    p_profile_id integer,
    p_limit integer,
    p_after_id text default null,
    p_after_type text default null
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
    if p_profile_id is null or p_profile_id not between 1 and 6
       or (p_after_id is null) <> (p_after_type is null) then
        raise exception using errcode = '22023', message = 'Invalid profile or library cursor';
    end if;
    return query
    select l.item_json
    from public.laqta_library as l
    where l.user_id = v_user_id
      and l.profile_index = p_profile_id
      and (p_after_id is null or (l.content_id, l.content_type) > (p_after_id, p_after_type))
    order by l.content_id, l.content_type
    limit greatest(1, least(coalesce(p_limit, 200), 1000));
end;
$$;

revoke all on function public.sync_pull_library_keyset(integer, integer, text, text) from public, anon;
grant execute on function public.sync_pull_library_keyset(integer, integer, text, text) to authenticated;
