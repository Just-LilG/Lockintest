-- Repeating locks that cross midnight (say every day 23:00 for 2 hours).
-- Before: _fire_due only looked at "today's" start, so if the 23:00 firing was missed (cron hiccup, or the
-- phone only asked after midnight), at 00:30 the lock was never made: 00:30 is before 23:00 today.
-- Now it checks yesterday's start too and fires it with the time that's left. last_fired_on now holds the
-- date the occurrence STARTED on, so yesterday's late lock doesn't block today's.

create or replace function public._fire_due(p_target uuid)
returns void language plpgsql security definer set search_path to '' as $$
declare
  s record;
  local_now timestamp;
  d date;
  start_local timestamp;
  start_ts timestamptz;
  v_allowed boolean;
  v_max int;
  v_pid uuid;
  v_other uuid;
begin
  for s in select * from public.lock_schedules where target = p_target loop
    local_now := now() at time zone s.tz;
    -- Yesterday first: a late run of last night's lock comes before today's.
    foreach d in array array[local_now::date - 1, local_now::date] loop
      continue when (s.days & (1 << (extract(isodow from d)::int - 1))) = 0;
      continue when s.last_fired_on is not distinct from d;
      start_local := d + make_interval(mins => s.start_minute);
      continue when local_now < start_local or local_now >= start_local + make_interval(secs => s.seconds);
      -- Still the same two people.
      select pr.pid, pr.other into v_pid, v_other from public._pair_of(s.locker) pr;
      continue when v_other is distinct from s.target;
      select pf.lock_allowed, pf.lock_max_seconds into v_allowed, v_max from public.profiles pf where pf.id = s.target;
      -- Not allowed: skip this one without trying again every minute.
      if v_allowed is false or s.seconds > coalesce(v_max, 28800) then
        update public.lock_schedules set last_fired_on = d where id = s.id;
        s.last_fired_on := d;
        continue;
      end if;
      perform public._lock_tidy(s.target);
      continue when exists (select 1 from public.partner_locks where target = s.target and ended_at is null);
      start_ts := start_local at time zone s.tz;
      begin
        insert into public.partner_locks (locker, target, seconds, starts_at, ends_at, message, strict, together)
        values (s.locker, s.target, s.seconds, start_ts, start_ts + make_interval(secs => s.seconds), s.message, s."strict", false);
      exception when unique_violation or raise_exception then
        -- Already locked, or an unpair request is open: leave this one. Anything else is a real error and surfaces.
        update public.lock_schedules set last_fired_on = d where id = s.id;
        s.last_fired_on := d;
        continue;
      end;
      update public.lock_schedules set last_fired_on = d where id = s.id;
      s.last_fired_on := d;
      insert into public.messages (partnership_id, sender, kind, body)
      values (v_pid, s.locker, 'event', 'lock_set|' || (s.seconds / 60)::text);
    end loop;
  end loop;
end $$;
