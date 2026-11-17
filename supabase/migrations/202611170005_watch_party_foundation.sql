create table if not exists public.laqta_watch_parties (
    id uuid primary key default gen_random_uuid(),
    code text not null unique check (code ~ '^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$'),
    owner_user_id uuid not null references auth.users(id) on delete cascade,
    host_member_id uuid not null,
    media jsonb not null check (jsonb_typeof(media) = 'object'),
    playback jsonb check (playback is null or jsonb_typeof(playback) = 'object'),
    host_only boolean not null default false,
    max_members integer not null default 3 check (max_members between 1 and 3),
    created_at timestamptz not null default now(),
    ended_at timestamptz
);

create table if not exists public.laqta_watch_party_members (
    id uuid primary key default gen_random_uuid(),
    party_id uuid not null references public.laqta_watch_parties(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    device_id text not null check (length(device_id) between 1 and 128),
    profile_index integer check (profile_index between 1 and 6),
    display_name text not null check (length(trim(display_name)) between 1 and 40),
    avatar_url text,
    avatar_color text,
    joined_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    removed_at timestamptz,
    unique (party_id, id),
    unique (party_id, user_id, device_id)
);

do $$
begin
    if not exists (
        select 1 from pg_catalog.pg_constraint
        where conrelid = 'public.laqta_watch_parties'::regclass
          and conname = 'laqta_watch_parties_host_member_fkey'
    ) then
        alter table public.laqta_watch_parties
            add constraint laqta_watch_parties_host_member_fkey
            foreign key (id, host_member_id)
            references public.laqta_watch_party_members (party_id, id)
            on delete cascade
            deferrable initially deferred;
    end if;
end;
$$;

create index if not exists laqta_watch_party_members_user_idx
    on public.laqta_watch_party_members (user_id, party_id)
    where removed_at is null;

alter table public.laqta_watch_parties enable row level security;
alter table public.laqta_watch_party_members enable row level security;
revoke all on public.laqta_watch_parties from public, anon, authenticated;
revoke all on public.laqta_watch_party_members from public, anon, authenticated;

create or replace function public.laqta_watch_party_is_member(p_party_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select auth.uid() is not null
       and coalesce((auth.jwt() ->> 'is_anonymous')::boolean, false) = false
       and exists (
           select 1
           from public.laqta_watch_party_members as m
           join public.laqta_watch_parties as p on p.id = m.party_id
           where m.party_id = p_party_id
             and m.user_id = auth.uid()
             and m.removed_at is null
             and p.ended_at is null
       );
$$;

revoke all on function public.laqta_watch_party_is_member(uuid) from public, anon;
grant execute on function public.laqta_watch_party_is_member(uuid) to authenticated;
