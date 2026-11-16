-- Tamper handling (0.22.0).
--
--   * A lock whose phone had a protection switched off for real (device admin, "display over other apps",
--     app-time access, LockIn's guard, a safe-mode restart, the clock moved) is VOIDED: it stays in the chat
--     as a warning, but its hours are not written as a session. "Went quiet" kinds (stopped, silent) and the
--     emergency dialer only warn: a dead battery or a call for help must not cost anyone their hours.
--   * protect_tamper(): the same warning outside a lock, when the phone is paired. Same kinds, minus the ones
--     that only make sense in a lock. At most one note per kind per 15 minutes, so a switch that keeps being
--     flipped does not fill the chat.
--   * device_ping(): a quiet "this phone is still here". The partner's phone list already shows "seen <ago>",
--     which now stays fresh while LockIn runs in the background, and goes stale when LockIn is stopped.

alter table public.partner_locks add column if not exists voided_at timestamptz;
alter table public.partner_locks add column if not exists void_kind text;

-- Which kinds void a lock.
create or replace function public._void_kind(k text)
returns boolean language sql immutable set search_path to '' as $$
  select k in ('admin_off', 'overlay_off', 'usage_off', 'guard_off', 'safe_mode', 'time_changed')
$$;

-- Voids [p_target]'s running lock (or the one that ended in the last hour, when the warning only now got through
-- from a phone that was offline) and takes back the session it wrote.
create or replace function public._lock_void(p_target uuid, k text)
returns void language plpgsql security definer set search_path to '' as $$
declare r record;
begin
  for r in
    update public.partner_locks
       set voided_at = coalesce(voided_at, now()), void_kind = coalesce(void_kind, k)
     where target = p_target and starts_at <= now()
       and (ended_at is null or ended_at > now() - interval '1 hour')
       and voided_at is null
    returning id
  loop
    delete from public.sessions where id = public._lock_session_id(r.id);
  end loop;
end $$;

-- A lock that was voided does not count as a session.
create or replace function public._lock_to_session()
returns trigger language plpgsql security definer set search_path to '' as $$
declare
  v_start timestamptz;
  v_held int;
  v_name text;
begin
  if old.ended_at is not null or new.ended_at is null then return new; end if;
  if new.voided_at is not null then return new; end if;
  begin
    v_start := coalesce(new.starts_at, new.started_at);
    v_held := floor(extract(epoch from (least(new.ended_at, new.ends_at) - v_start)))::int;
    if v_start is not null and v_held >= 30 then
      select nullif(btrim(p.display_name), '') into v_name from public.profiles p where p.id = new.locker;
      insert into public.sessions (id, user_id, started_at, seconds, pickups, title)
      values (public._lock_session_id(new.id), new.target, v_start, v_held, coalesce(new.pickups, 0),
              'Locked by ' || coalesce(v_name, 'your partner'))
      on conflict (id) do nothing;
    end if;
  exception when others then
    raise warning 'lock % not recorded as a session: %', new.id, sqlerrm;
  end;
  return new;
end $$;

revoke all on function public._void_kind(text) from public, anon, authenticated;
revoke all on function public._lock_void(uuid, text) from public, anon, authenticated;

-- The locked phone's warning (as before), now also voiding the lock for the kinds above.
create or replace function public.lock_tamper(p_kind text, p_detail text default '')
returns bigint language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); pid uuid; mid bigint; k text := left(coalesce(p_kind, ''), 20); d text := left(regexp_replace(coalesce(p_detail, ''), '[^0-9]', '', 'g'), 6);
begin
  if me is null then raise exception 'not_signed_in'; end if;
  if k not in ('stopped', 'overlay_off', 'admin_off', 'time_changed', 'emergency_dial', 'safe_mode', 'usage_off', 'guard_off', 'silent') then raise exception 'bad_kind'; end if;
  if not exists (select 1 from public.partner_locks where target = me and starts_at <= now()
                  and (ended_at is null or ended_at > now() - interval '1 hour')) then
    return null;
  end if;
  select pr.pid into pid from public._pair_of(me) pr;
  if pid is null then return null; end if;
  if public._void_kind(k) then perform public._lock_void(me, k); end if;
  insert into public.messages (partnership_id, sender, kind, body)
  values (pid, me, 'event', 'lock_tamper|' || k || case when d <> '' then '|' || d else '' end)
  returning id into mid;
  return mid;
end $$;

-- The same warning with no lock running: protection was switched off on a paired phone.
create or replace function public.protect_tamper(p_kind text, p_detail text default '')
returns bigint language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); pid uuid; mid bigint; k text := left(coalesce(p_kind, ''), 20); d text := left(regexp_replace(coalesce(p_detail, ''), '[^0-9]', '', 'g'), 6);
begin
  if me is null then raise exception 'not_signed_in'; end if;
  if k not in ('admin_off', 'overlay_off', 'usage_off', 'guard_off', 'safe_mode') then raise exception 'bad_kind'; end if;
  select pr.pid into pid from public._pair_of(me) pr;
  if pid is null then return null; end if;
  -- A lock that is (or just was) on this phone: it is voided too, whichever way the warning arrived.
  if public._void_kind(k) then perform public._lock_void(me, k); end if;
  if exists (select 1 from public.messages m
              where m.partnership_id = pid and m.sender = me and m.kind = 'event'
                and m.body like ('protect\_tamper|' || k || '%') and m.created_at > now() - interval '15 minutes') then
    return null;
  end if;
  insert into public.messages (partnership_id, sender, kind, body)
  values (pid, me, 'event', 'protect_tamper|' || k || case when d <> '' then '|' || d else '' end)
  returning id into mid;
  return mid;
end $$;

-- This phone is still here.
create or replace function public.device_ping(p_device text)
returns void language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); d text := left(btrim(coalesce(p_device, '')), 64);
begin
  if me is null then raise exception 'not_signed_in'; end if;
  if length(d) >= 8 then
    update public.devices set last_seen = now() where user_id = me and device_id = d;
  end if;
end $$;

revoke all on function public.lock_tamper(text, text) from public, anon;
revoke all on function public.protect_tamper(text, text) from public, anon;
revoke all on function public.device_ping(text) from public, anon;
grant execute on function public.lock_tamper(text, text) to authenticated;
grant execute on function public.protect_tamper(text, text) to authenticated;
grant execute on function public.device_ping(text) to authenticated;
