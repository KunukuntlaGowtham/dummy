-- Gowtham: ADMIN REMOTE CONTROL. Paste ALL of this into Supabase -> SQL Editor -> Run.
-- Safe to run again (it updates what's there). Run setup.sql once before the first time.
--
-- What it does:
--   * each phone can be STOPPED, or have single buttons turned off, from the admin app
--   * a message you set is shown on the stopped phone
--   * "stop everyone" with one switch; remove a person (all their phones) in one tap
--   * the app reads its orders on each licence check (15 min) and on a fast poll (~30 s):
--       stop, block, licence removed, licence ended and the kill switch all land within ~30 s
--   * only emails listed in public.admins can change anything; the app reads only its own orders

-- ---- the orders, kept per phone ----
alter table public.license_devices add column if not exists stopped       boolean not null default false;
alter table public.license_devices add column if not exists buttons_off   text[]  not null default '{}';
alter table public.license_devices add column if not exists admin_message text    not null default '';

-- "stop everyone" (softer than the kill switch: the buttons stop, the app still opens).
insert into public.app_config (key, value) values ('stop_all', 'off') on conflict do nothing;

-- ---- who may control ----
create table if not exists public.admins (email text primary key);
alter table public.admins enable row level security;   -- closed to the app; only the functions below read it
-- Add an admin (use the SAME email you sign into the admin app with), then make its account
-- (Authentication -> Users -> Add user; the email must also be on public.licenses):
--   insert into public.admins   (email) values ('you@example.com') on conflict do nothing;
--   insert into public.licenses (email) values ('you@example.com') on conflict do nothing;
delete from public.admins where lower(email) = 'change_me@example.com';   -- the old placeholder, if it got in

create or replace function public.is_admin() returns boolean
language sql security definer set search_path = public as $$
  select exists (select 1 from public.admins a where lower(a.email) = lower(coalesce(auth.jwt() ->> 'email','')));
$$;

-- ---- a phone's orders (used by check_license and the fast poll) ----
-- Stopped when: kill switch on, no / removed / ended licence, phone blocked, everyone stopped,
-- or this phone stopped. The message is the one you set for the phone, else the reason.
create or replace function public.orders_for(uid uuid, dev_id text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  d    public.license_devices%rowtype;
  lic  public.licenses%rowtype;
  mail text;
  why  text;
begin
  select * into d from public.license_devices where user_id = uid and device_id = dev_id;
  select lower(email) into mail from auth.users where id = uid;
  select * into lic from public.licenses where lower(email) = mail;
  if coalesce((select value from public.app_config where key = 'kill'), 'off') = 'on' then
    why := 'The app is switched off for now';
  elsif lic.email is null or not lic.active then
    why := 'This login has no licence';
  elsif lic.expires_at is not null and lic.expires_at < now() then
    why := 'Your licence has ended';
  elsif coalesce(d.revoked, false) then
    why := 'This phone was blocked by the admin';
  elsif coalesce((select value from public.app_config where key = 'stop_all'), 'off') = 'on' then
    why := 'Stopped by the admin';
  elsif coalesce(d.stopped, false) then
    why := 'Stopped by the admin';
  end if;
  return jsonb_build_object(
    'stopped', why is not null,
    'off',     to_jsonb(coalesce(d.buttons_off, '{}'::text[])),
    'msg',     coalesce(nullif(d.admin_message, ''), why, '')
  );
end $$;
-- Internal only: called by the functions below, never straight from the app.
revoke all on function public.orders_for(uuid, text) from public, anon, authenticated;

-- ---- check_license also returns the orders (replaces the one in setup.sql) ----
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
  return jsonb_build_object('ok', true) || public.orders_for(uid, p_device);
end $$;
revoke all on function public.check_license(text, text) from public, anon;
grant execute on function public.check_license(text, text) to authenticated;

-- ---- the fast poll the app asks every ~30 s while it is on (cheap: just the orders) ----
create or replace function public.device_commands(p_device text)
returns jsonb language plpgsql security definer set search_path = public as $$
declare uid uuid := auth.uid();
begin
  if uid is null or p_device is null then return jsonb_build_object('stopped', false); end if;
  update public.license_devices set last_seen = now() where user_id = uid and device_id = p_device;
  return public.orders_for(uid, p_device);
end $$;
revoke all on function public.device_commands(text) from public, anon;
grant execute on function public.device_commands(text) to authenticated;

-- ======================= the admin app's calls (only admins) =======================

-- Every phone, with its person, licence state and orders - for the admin app's list.
drop function if exists public.admin_list_devices();
create function public.admin_list_devices()
returns table (email text, model text, device_id text, first_seen timestamptz, last_seen timestamptz,
               revoked boolean, stopped boolean, buttons_off text[], admin_message text,
               active boolean, max_devices int)
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'Not an admin'; end if;
  return query
    select lower(u.email)::text, coalesce(d.model, '')::text, d.device_id::text, d.first_seen, d.last_seen,
           d.revoked, d.stopped, d.buttons_off, d.admin_message::text,
           coalesce(l.active, false), coalesce(l.max_devices, 0)
    from public.license_devices d
    join auth.users u on u.id = d.user_id
    left join public.licenses l on lower(l.email) = lower(u.email)
    order by d.last_seen desc;
end $$;
revoke all on function public.admin_list_devices() from public, anon;
grant execute on function public.admin_list_devices() to authenticated;

-- The switches that act on everyone, for the top of the admin app.
create or replace function public.admin_status()
returns jsonb language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'Not an admin'; end if;
  return jsonb_build_object(
    'stop_all', coalesce((select value from public.app_config where key = 'stop_all'), 'off') = 'on',
    'kill',     coalesce((select value from public.app_config where key = 'kill'), 'off') = 'on');
end $$;
revoke all on function public.admin_status() from public, anon;
grant execute on function public.admin_status() to authenticated;

-- Set one phone's orders (stop it, turn buttons off, set its message).
create or replace function public.admin_set_device(p_device text, p_stopped boolean,
               p_off text[] default '{}', p_message text default '')
returns void language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'Not an admin'; end if;
  update public.license_devices
    set stopped = p_stopped, buttons_off = coalesce(p_off, '{}'), admin_message = coalesce(p_message, '')
    where device_id = p_device;
end $$;
revoke all on function public.admin_set_device(text, boolean, text[], text) from public, anon;
grant execute on function public.admin_set_device(text, boolean, text[], text) to authenticated;

-- Block / free one phone. (A blocked phone frees its place: to stop the PERSON, remove the licence.)
create or replace function public.admin_revoke_device(p_device text, p_revoked boolean)
returns void language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'Not an admin'; end if;
  update public.license_devices set revoked = p_revoked where device_id = p_device;
end $$;
revoke all on function public.admin_revoke_device(text, boolean) from public, anon;
grant execute on function public.admin_revoke_device(text, boolean) to authenticated;

-- Remove / restore a person: every phone of that login stops, and no new phone can join.
create or replace function public.admin_set_license(p_email text, p_active boolean)
returns void language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'Not an admin'; end if;
  -- Matched without case (an email may have been listed with capitals).
  update public.licenses set active = p_active where lower(email) = lower(p_email);
  if not found then
    insert into public.licenses (email, active) values (lower(p_email), p_active);
  end if;
end $$;
revoke all on function public.admin_set_license(text, boolean) from public, anon;
grant execute on function public.admin_set_license(text, boolean) to authenticated;

-- Stop (or free) everyone at once.
create or replace function public.admin_stop_all(p_on boolean)
returns void language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'Not an admin'; end if;
  insert into public.app_config (key, value) values ('stop_all', case when p_on then 'on' else 'off' end)
    on conflict (key) do update set value = excluded.value;
end $$;
revoke all on function public.admin_stop_all(boolean) from public, anon;
grant execute on function public.admin_stop_all(boolean) to authenticated;

-- ============ reminders ============
--  button keys for buttons_off: scan, tick, fast, clear, book, go, auto, report, delete, watch
--  stop everyone (SQL):     update public.app_config set value = 'on' where key = 'stop_all';   (free: 'off')
