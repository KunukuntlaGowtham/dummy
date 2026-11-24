-- A11y Inspector: sign-in protection. Paste ALL of this into Supabase -> SQL Editor -> Run. Once.
--
-- What it does:
--   * only emails on the list `licenses` can make an account (open sign-ups are closed)
--   * every account that already exists is put on the list for you (nobody is locked out)
--   * the app asks `check_license()` after sign-in and every 15 min; the server says yes/no:
--       on the list + active + not ended + the phone is one of its allowed phones (2) + not switched off
--   * you can see every phone and every refusal, and remove a person, a phone, or switch all off

-- ---- the tables (closed to the app: RLS on, no policies - only the functions below touch them) ----
create table if not exists public.licenses (
  email        text primary key,
  active       boolean not null default true,        -- false = removed
  max_devices  int     not null default 2,           -- phones per person
  expires_at   timestamptz,                          -- empty = no end date
  note         text,
  created_at   timestamptz not null default now()
);
alter table public.licenses enable row level security;

create table if not exists public.license_devices (
  user_id    uuid not null references auth.users on delete cascade,
  device_id  text not null,
  model      text,
  revoked    boolean not null default false,         -- true = this phone is blocked / its place is free
  first_seen timestamptz not null default now(),
  last_seen  timestamptz not null default now(),
  primary key (user_id, device_id)
);
alter table public.license_devices enable row level security;

create table if not exists public.license_checks (
  id        bigserial primary key,
  at        timestamptz not null default now(),
  user_id   uuid,
  email     text,
  device_id text,
  result    text,
  detail    text
);
alter table public.license_checks enable row level security;

create table if not exists public.app_config (key text primary key, value text not null);
alter table public.app_config enable row level security;
insert into public.app_config (key, value) values ('kill', 'off') on conflict do nothing;

-- The app also writes which phones use a login (informational).
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
drop policy if exists "devices: own insert" on public.devices;
drop policy if exists "devices: own update" on public.devices;
drop policy if exists "devices: own select" on public.devices;
create policy "devices: own insert" on public.devices for insert to authenticated with check (auth.uid() = user_id);
create policy "devices: own update" on public.devices for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "devices: own select" on public.devices for select to authenticated using (auth.uid() = user_id);

-- ---- everyone who has an account now is allowed (so nobody is locked out) ----
insert into public.licenses (email)
  select lower(email) from auth.users where email is not null
  on conflict do nothing;

-- ---- closed doors: no new account unless the email is on the list ----
create or replace function public.only_licensed_signups() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from public.licenses l where lower(l.email) = lower(new.email) and l.active) then
    raise exception 'This email is not on the licence list';
  end if;
  return new;
end $$;
drop trigger if exists only_licensed_signups on auth.users;
create trigger only_licensed_signups before insert on auth.users
  for each row execute function public.only_licensed_signups();

-- ---- the check the app asks (answers only for the signed-in login itself) ----
create or replace function public.check_license(p_device text, p_model text default '')
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  uid  uuid := auth.uid();
  mail text := lower(coalesce(auth.jwt() ->> 'email', ''));
  lic  public.licenses%rowtype;
  dev  public.license_devices%rowtype;
  n    int;
  why  text;
begin
  if uid is null or p_device is null or length(p_device) < 8 then
    return jsonb_build_object('ok', false, 'message', 'Sign in again');
  end if;

  if (select value from public.app_config where key = 'kill') = 'on' then
    why := 'The app is switched off for now';
  else
    select * into lic from public.licenses where lower(email) = mail;
    if not found or not lic.active then
      why := 'This login has no licence';
    elsif lic.expires_at is not null and lic.expires_at < now() then
      why := 'Your licence has ended';
    else
      select * into dev from public.license_devices where user_id = uid and device_id = p_device;
      if found then
        if dev.revoked then
          why := 'This phone was removed from your licence';
        else
          update public.license_devices set last_seen = now(), model = p_model where user_id = uid and device_id = p_device;
        end if;
      else
        select count(*) into n from public.license_devices where user_id = uid and not revoked;
        if n >= lic.max_devices then
          why := 'This licence is already on ' || lic.max_devices || ' phones';
        else
          insert into public.license_devices (user_id, device_id, model) values (uid, p_device, p_model);
        end if;
      end if;
    end if;
  end if;

  insert into public.license_checks (user_id, email, device_id, result, detail)
    values (uid, mail, p_device, case when why is null then 'ok' else 'refused' end, why);
  if why is not null then
    return jsonb_build_object('ok', false, 'message', why);
  end if;
  return jsonb_build_object('ok', true);
end $$;

revoke all on function public.check_license(text, text) from public, anon;
grant execute on function public.check_license(text, text) to authenticated;

-- ======================= your everyday commands (run any when you want) =======================
--  add a person:        insert into public.licenses (email) values ('friend@example.com');
--  remove a person:     update public.licenses set active = false where email = 'friend@example.com';
--  more phones for one: update public.licenses set max_devices = 3 where email = 'friend@example.com';
--  an end date:         update public.licenses set expires_at = '2026-12-31' where email = 'friend@example.com';
--  who uses what:       select l.email, d.model, d.last_seen, d.revoked from public.license_devices d
--                         join auth.users u on u.id = d.user_id join public.licenses l on lower(l.email) = lower(u.email)
--                         order by d.last_seen desc;
--  block / free a phone: update public.license_devices set revoked = true where device_id = '...';
--  switch ALL apps off: update public.app_config set value = 'on'  where key = 'kill';   (back: 'off')
--  recent refusals:     select * from public.license_checks where result <> 'ok' order by at desc limit 50;
