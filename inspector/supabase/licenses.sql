-- A11y Inspector licences (run once in Supabase -> SQL Editor, after devices.sql).
-- Everything here is closed to the app: RLS is on and there are NO policies, so only the Edge
-- Function (service role) reads or writes these tables. The phone never sees them.

-- Who may use the app: one row per allowed email.
create table if not exists public.licenses (
  email        text primary key,
  active       boolean not null default true,       -- false = revoked
  plan         text    not null default 'standard',
  max_devices  int     not null default 2,
  expires_at   timestamptz,                         -- null = no end date
  note         text,
  created_at   timestamptz not null default now()
);
alter table public.licenses enable row level security;

-- The phones a login is bound to (the public half of each phone's Keystore key).
create table if not exists public.license_devices (
  user_id    uuid not null references auth.users on delete cascade,
  device_id  text not null,
  public_key text not null,
  model      text,
  revoked    boolean not null default false,        -- set true to free a slot / block a phone
  first_seen timestamptz not null default now(),
  last_seen  timestamptz not null default now(),
  primary key (user_id, device_id)
);
alter table public.license_devices enable row level security;

-- One-time challenges (a signed answer is accepted once, within 5 minutes).
create table if not exists public.license_nonces (
  nonce      text primary key,
  user_id    uuid not null,
  created_at timestamptz not null default now()
);
alter table public.license_nonces enable row level security;

-- Every licence check, for you to look at (many phones on one login, refusals ...).
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

-- Kill switch: update public.app_config set value = 'on' where key = 'kill';  (every app locks at its next check)
create table if not exists public.app_config (
  key   text primary key,
  value text not null
);
alter table public.app_config enable row level security;
insert into public.app_config (key, value) values ('kill', 'off') on conflict do nothing;

-- Closed doors: nobody can make an account unless the email is on the list (this stops
-- open sign-ups even if "Allow new users to sign up" is left on). Add people with:
--   insert into public.licenses (email) values ('friend@example.com');
create or replace function public.only_licensed_signups() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from public.licenses l
                 where lower(l.email) = lower(new.email) and l.active) then
    raise exception 'This email is not on the licence list';
  end if;
  return new;
end $$;

drop trigger if exists only_licensed_signups on auth.users;
create trigger only_licensed_signups before insert on auth.users
  for each row execute function public.only_licensed_signups();

-- Handy looks:
--   Logins on several phones:
--     select u.email, count(*) phones from public.license_devices d join auth.users u on u.id = d.user_id
--       where not d.revoked group by u.email having count(*) > 1;
--   Revoke a person:   update public.licenses set active = false where email = 'x@y.z';
--   Free a phone slot: update public.license_devices set revoked = true where device_id = '...';
--   Recent refusals:   select * from public.license_checks where result <> 'ok' order by at desc limit 50;
