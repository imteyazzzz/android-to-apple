-- Android to Apple: namaz and Ramadan times kept in Supabase, edited from a phone, fetched by the TV.
-- Run once in Supabase: Dashboard -> SQL Editor -> New query -> paste -> Run.
-- Before running, replace YOUR_EMAIL@example.com (two places below) with the email you will log in with.

create table if not exists public.tv_namaz (
  id          int primary key default 1 check (id = 1),   -- one row only
  data        jsonb not null default '{}'::jsonb,
  updated_at  timestamptz not null default now()
);

-- updated_at moves on every change, so the TV only applies new data.
create or replace function public.tv_namaz_touch() returns trigger
language plpgsql set search_path = '' as $$
begin
  new.updated_at := now();
  return new;
end $$;

drop trigger if exists tv_namaz_touch on public.tv_namaz;
create trigger tv_namaz_touch before update on public.tv_namaz
  for each row execute function public.tv_namaz_touch();

alter table public.tv_namaz enable row level security;

-- The TV reads with the public anon key.
drop policy if exists "anyone can read" on public.tv_namaz;
create policy "anyone can read" on public.tv_namaz
  for select to anon, authenticated using (true);

-- Only you (logged in on the phone page) can change it.
drop policy if exists "owner can update" on public.tv_namaz;
create policy "owner can update" on public.tv_namaz
  for update to authenticated
  using ((select auth.jwt() ->> 'email') = 'YOUR_EMAIL@example.com')
  with check ((select auth.jwt() ->> 'email') = 'YOUR_EMAIL@example.com');

-- Starting values (examples: change them from the phone page).
insert into public.tv_namaz (id, data) values (1, '{
  "fajr": "05:30", "zuhr": "13:30", "asr": "16:30", "maghrib": "17:40", "isha": "19:30", "jumuah": "13:30",
  "alert_on": true, "alert_before_min": 5, "lock_min": 15, "show_on_screensaver": true,
  "ramadan_start": "2027-02-09", "ramadan_days": 30,
  "sehri_first": "05:35", "sehri_last": "05:05", "iftar_first": "18:10", "iftar_last": "18:28",
  "iftar_countdown_min": 10
}'::jsonb)
on conflict (id) do nothing;
