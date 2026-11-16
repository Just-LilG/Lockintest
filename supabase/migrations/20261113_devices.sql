-- Devices: every phone you use LockIn on, so your partner can see them all and lock them all.
--
-- A lock is on the person, so every device signed in to that account is locked together. What this adds:
--   * devices: each phone checks in (name, model, Android version, app version, last seen);
--   * lock_device_beats: during a lock, each device's own check-ins and escapes, so the locker sees which
--     devices are holding the lock and which have gone quiet;
--   * partner_devices() for the locker, my_devices() for you, device_forget() to remove one of your own.

create table if not exists public.devices (
  user_id uuid not null references auth.users(id) on delete cascade,
  device_id text not null,
  name text not null,
  model text not null default '',
  os text not null default '',
  app_version text not null default '',
  first_seen timestamptz not null default now(),
  last_seen timestamptz not null default now(),
  primary key (user_id, device_id)
);
alter table public.devices enable row level security;
revoke all on public.devices from anon, authenticated;

create table if not exists public.lock_device_beats (
  lock_id bigint not null references public.partner_locks(id) on delete cascade,
  device_id text not null,
  last_beat timestamptz not null default now(),
  escapes int not null default 0,
  primary key (lock_id, device_id)
);
alter table public.lock_device_beats enable row level security;
revoke all on public.lock_device_beats from anon, authenticated;

create or replace function public.device_checkin(p_device text, p_name text, p_model text default '', p_os text default '', p_app text default '')
returns void language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); d text := left(btrim(coalesce(p_device, '')), 64);
begin
  if me is null then raise exception 'not_signed_in'; end if;
  if length(d) < 8 then raise exception 'bad_device'; end if;
  insert into public.devices (user_id, device_id, name, model, os, app_version)
  values (me, d, coalesce(nullif(left(btrim(coalesce(p_name, '')), 60), ''), 'Android phone'),
          left(coalesce(p_model, ''), 60), left(coalesce(p_os, ''), 30), left(coalesce(p_app, ''), 20))
  on conflict (user_id, device_id) do update
    set name = excluded.name, model = excluded.model, os = excluded.os, app_version = excluded.app_version, last_seen = now();
  -- Keep the 10 most recent devices.
  delete from public.devices where user_id = me and device_id in (
    select device_id from public.devices where user_id = me order by last_seen desc offset 10);
end $$;

create or replace function public.device_forget(p_device text)
returns void language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid();
begin
  if me is null then raise exception 'not_signed_in'; end if;
  delete from public.devices where user_id = me and device_id = p_device;
end $$;

-- The locked phone's check-in, now per device. p_device is optional so older app versions still work.
drop function if exists public.lock_beat(int, int);
create or replace function public.lock_beat(p_pickups int, p_escapes int, p_device text default null)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_id bigint; v_left int; d text := left(btrim(coalesce(p_device, '')), 64);
begin
  if me is null then raise exception 'not_signed_in'; end if;
  update public.partner_locks
     set last_beat = now(),
         pickups = greatest(pickups, least(greatest(coalesce(p_pickups, 0), 0), 999)),
         escapes = greatest(escapes, least(greatest(coalesce(p_escapes, 0), 0), 9999))
   where target = me and ended_at is null and starts_at <= now()
  returning id, ceil(extract(epoch from (ends_at - now())))::int into v_id, v_left;
  if length(d) >= 8 then
    update public.devices set last_seen = now() where user_id = me and device_id = d;
    if v_id is not null then
      insert into public.lock_device_beats (lock_id, device_id, last_beat, escapes)
      values (v_id, d, now(), least(greatest(coalesce(p_escapes, 0), 0), 9999))
      on conflict (lock_id, device_id) do update
        set last_beat = now(), escapes = greatest(public.lock_device_beats.escapes, excluded.escapes);
    end if;
  end if;
  if v_id is not null then
    return jsonb_build_object('state', 'active', 'id', v_id, 'remaining', greatest(v_left, 0));
  end if;
  return jsonb_build_object('state', 'none');
end $$;

-- My partner's devices, and during a lock I set, how each one is holding it.
create or replace function public.partner_devices()
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_other uuid; v_lock bigint;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.other into v_other from public._pair_of(me) pr;
  if v_other is null then return '[]'::jsonb; end if;
  select id into v_lock from public.partner_locks where locker = me and target = v_other and ended_at is null and starts_at <= now();
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'id', d.device_id, 'name', d.name, 'model', d.model, 'os', d.os, 'app', d.app_version,
      'seen_ago', extract(epoch from (now() - d.last_seen))::int,
      'locked', v_lock is not null,
      'beat_ago', case when b.last_beat is null then -1 else extract(epoch from (now() - b.last_beat))::int end,
      'escapes', coalesce(b.escapes, 0)) order by d.last_seen desc)
    from public.devices d
    left join public.lock_device_beats b on b.lock_id = v_lock and b.device_id = d.device_id
    where d.user_id = v_other), '[]'::jsonb);
end $$;

create or replace function public.my_devices()
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid();
begin
  if me is null then raise exception 'not_signed_in'; end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'id', d.device_id, 'name', d.name, 'model', d.model, 'os', d.os, 'app', d.app_version,
      'seen_ago', extract(epoch from (now() - d.last_seen))::int) order by d.last_seen desc)
    from public.devices d where d.user_id = me), '[]'::jsonb);
end $$;

revoke all on function public.device_checkin(text, text, text, text, text) from public, anon;
revoke all on function public.device_forget(text) from public, anon;
revoke all on function public.lock_beat(int, int, text) from public, anon;
revoke all on function public.partner_devices() from public, anon;
revoke all on function public.my_devices() from public, anon;
grant execute on function public.device_checkin(text, text, text, text, text) to authenticated;
grant execute on function public.device_forget(text) to authenticated;
grant execute on function public.lock_beat(int, int, text) to authenticated;
grant execute on function public.partner_devices() to authenticated;
grant execute on function public.my_devices() to authenticated;
