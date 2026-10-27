-- A11y Inspector: which phones use each login (run once in Supabase → SQL Editor).
create table if not exists public.devices (
  user_id     uuid not null references auth.users on delete cascade,
  device_id   text not null,
  email       text,
  model       text,
  android     text,
  app_version text,
  first_seen  timestamptz not null default now(),
  last_seen   timestamptz not null default now(),
  primary key (user_id, device_id)
);

alter table public.devices enable row level security;

-- Each signed-in phone may write and read only its own login's rows.
create policy "devices: own insert" on public.devices
  for insert to authenticated with check (auth.uid() = user_id);
create policy "devices: own update" on public.devices
  for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "devices: own select" on public.devices
  for select to authenticated using (auth.uid() = user_id);

-- Logins used on more than one phone (run this whenever you want to look):
-- select email, count(*) as phones, string_agg(model || ' (last ' || to_char(last_seen, 'DD Mon HH24:MI') || ')', ', ') as which
-- from public.devices group by email having count(*) > 1 order by phones desc;
