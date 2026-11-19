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
    v_authoritative_playback jsonb;
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

    if p_playback is not null and jsonb_typeof(p_playback) = 'object' then
        select playback into v_authoritative_playback
        from public.laqta_watch_parties
        where id = p_party_id;
        perform public.laqta_party_broadcast(p_party_id, 'state', v_authoritative_playback);
    end if;

    return public.laqta_party_snapshot(p_party_id, v_member_id);
end;
$$;

revoke all on function public.watch_party_update(uuid, text, jsonb, jsonb) from public, anon;
grant execute on function public.watch_party_update(uuid, text, jsonb, jsonb) to authenticated;
