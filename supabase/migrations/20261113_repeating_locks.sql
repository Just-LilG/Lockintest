-- Repeating locks: "every weekday at 8 pm for 2 hours".
--
-- A schedule belongs to the locker and targets their partner. It fires two ways, whichever comes first:
--   * pg_cron runs _fire_due_all() every minute, so the lock row exists on time even if the locked phone is off;
--   * my_lock() / their_lock() fire anything due for the person being asked about, so a phone that wakes on its
--     own alarm at the start time gets the lock that same second.
-- A schedule obeys the same rules as a one-off lock: the target's "allow locks" switch and longest length, one
-- lock at a time, nothing new while an unpair request from the target is open. It fires at most once a day,
-- even if that day's lock ended early. Only the locker can delete it; unpairing deletes it.

create extension if not exists pg_cron;

create table if not exists public.lock_schedules (
  id bigint generated always as identity primary key,
  locker uuid not null references auth.users(id) on delete cascade,
  target uuid not null references auth.users(id) on delete cascade,
  days smallint not null check (days between 1 and 127),          -- bit 0 Monday ... bit 6 Sunday
  start_minute smallint not null check (start_minute between 0 and 1439),
  seconds integer not null check (seconds between 300 and 28800),
  tz text not null,
  message text,
  strict boolean not null default false,
  last_fired_on date,
  created_at timestamptz not null default now()
);
create index if not exists lock_schedules_target on public.lock_schedules (target);
alter table public.lock_schedules enable row level security;
revoke all on public.lock_schedules from anon, authenticated;

-- Fires whatever is due now for one person. ("strict" is quoted: it's a reserved word in PL/pgSQL.)
create or replace function public._fire_due(p_target uuid)
returns void language plpgsql security definer set search_path to '' as $$
declare
  s record;
  local_now timestamp;
  today date;
  minute_now int;
  start_ts timestamptz;
  v_allowed boolean;
  v_max int;
  v_pid uuid;
  v_other uuid;
begin
  for s in select * from public.lock_schedules where target = p_target loop
    local_now := now() at time zone s.tz;
    today := local_now::date;
    minute_now := extract(hour from local_now)::int * 60 + extract(minute from local_now)::int;
    continue when (s.days & (1 << (extract(isodow from local_now)::int - 1))) = 0;
    continue when s.last_fired_on is not distinct from today;
    continue when minute_now < s.start_minute or minute_now >= s.start_minute + s.seconds / 60;
    -- Still the same two people.
    select pr.pid, pr.other into v_pid, v_other from public._pair_of(s.locker) pr;
    continue when v_other is distinct from s.target;
    select pf.lock_allowed, pf.lock_max_seconds into v_allowed, v_max from public.profiles pf where pf.id = s.target;
    -- Not allowed today: skip today without trying again every minute.
    if v_allowed is false or s.seconds > coalesce(v_max, 28800) then
      update public.lock_schedules set last_fired_on = today where id = s.id;
      continue;
    end if;
    perform public._lock_tidy(s.target);
    continue when exists (select 1 from public.partner_locks where target = s.target and ended_at is null);
    start_ts := (today + make_interval(mins => s.start_minute)) at time zone s.tz;
    begin
      insert into public.partner_locks (locker, target, seconds, starts_at, ends_at, message, strict, together)
      values (s.locker, s.target, s.seconds, start_ts, start_ts + make_interval(secs => s.seconds), s.message, s."strict", false);
    exception when unique_violation or raise_exception then
      -- Already locked, or an unpair request is open: leave it for today. Anything else is a real error and surfaces.
      update public.lock_schedules set last_fired_on = today where id = s.id;
      continue;
    end;
    update public.lock_schedules set last_fired_on = today where id = s.id;
    insert into public.messages (partnership_id, sender, kind, body)
    values (v_pid, s.locker, 'event', 'lock_set|' || (s.seconds / 60)::text);
  end loop;
end $$;

create or replace function public._fire_due_all()
returns void language plpgsql security definer set search_path to '' as $$
declare t uuid;
begin
  for t in select distinct target from public.lock_schedules loop
    perform public._fire_due(t);
  end loop;
end $$;

create or replace function public.schedule_add(p_days int, p_start_minute int, p_seconds int, p_tz text,
                                               p_message text default null, p_strict boolean default false)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid; v_allowed boolean; v_max int; v_id bigint; v_mid bigint;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_other is null then raise exception 'not_paired'; end if;
  if p_days is null or p_days < 1 or p_days > 127 then raise exception 'bad_days'; end if;
  if p_start_minute is null or p_start_minute < 0 or p_start_minute > 1439 then raise exception 'bad_time'; end if;
  if p_seconds is null or p_seconds < 300 or p_seconds > 28800 then raise exception 'bad_lock'; end if;
  if p_tz is null or not exists (select 1 from pg_catalog.pg_timezone_names where name = p_tz) then raise exception 'bad_tz'; end if;
  select pf.lock_allowed, pf.lock_max_seconds into v_allowed, v_max from public.profiles pf where pf.id = v_other;
  if v_allowed is false then raise exception 'lock_off'; end if;
  if p_seconds > coalesce(v_max, 28800) then raise exception 'over_max'; end if;
  if exists (select 1 from public.partnerships where id = v_pid and unpair_by = v_other) then raise exception 'unpair_pending'; end if;
  if (select count(*) from public.lock_schedules where locker = me and target = v_other) >= 8 then raise exception 'too_many_repeats'; end if;
  insert into public.lock_schedules (locker, target, days, start_minute, seconds, tz, message, strict)
  values (me, v_other, p_days, p_start_minute, p_seconds, p_tz, nullif(left(btrim(coalesce(p_message, '')), 140), ''), coalesce(p_strict, false))
  returning id into v_id;
  insert into public.messages (partnership_id, sender, kind, body)
  values (v_pid, me, 'event', 'lock_repeat|' || (p_seconds / 60)::text || '|' || p_days::text || '|' || p_start_minute::text)
  returning id into v_mid;
  -- If it's due right now (say "every day at this minute"), start it straight away.
  perform public._fire_due(v_other);
  return jsonb_build_object('id', v_id, 'note', v_mid);
end $$;

create or replace function public.schedule_remove(p_id bigint)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); s record; v_pid uuid; v_mid bigint;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  delete from public.lock_schedules where id = p_id and locker = me returning * into s;
  if not found then raise exception 'no_schedule'; end if;
  select pr.pid into v_pid from public._pair_of(me) pr;
  if v_pid is not null then
    insert into public.messages (partnership_id, sender, kind, body)
    values (v_pid, me, 'event', 'lock_repeat_off|' || (s.seconds / 60)::text || '|' || s.days::text || '|' || s.start_minute::text)
    returning id into v_mid;
  end if;
  return jsonb_build_object('note', v_mid);
end $$;

-- Both sides: the repeats I set on my partner (mine = true) and the ones set on me.
create or replace function public.lock_schedules()
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid();
begin
  if me is null then raise exception 'not_signed_in'; end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'id', s.id, 'mine', s.locker = me, 'days', s.days, 'start_minute', s.start_minute, 'seconds', s.seconds,
      'tz', s.tz, 'message', coalesce(s.message, ''), 'strict', s.strict) order by s.start_minute, s.id)
    from public.lock_schedules s where s.locker = me or s.target = me), '[]'::jsonb);
end $$;

-- Unpairing also removes the repeats between the two.
create or replace function public._unpair(p_pid uuid)
returns void language plpgsql security definer set search_path to '' as $$
declare a uuid; b uuid;
begin
  select owner, partner into a, b from public.partnerships where id = p_pid;
  if a is null then return; end if;
  update public.partner_locks set ended_at = now(), ended_by = 'unpaired'
   where ended_at is null and ((locker = a and target = b) or (locker = b and target = a));
  delete from public.lock_schedules where (locker = a and target = b) or (locker = b and target = a);
  delete from public.partnerships where id = p_pid;
end $$;

-- my_lock / their_lock: fire anything due first, so a phone waking on its alarm finds the lock at once.
create or replace function public.my_lock()
 returns jsonb language plpgsql security definer set search_path to '' as $function$
declare
  me uuid := auth.uid();
  r record;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  perform public._lock_tidy(me);
  perform public._fire_due(me);
  select l.id as lock_id, l.seconds as total, ceil(extract(epoch from (l.ends_at - now())))::int as left_s,
         greatest(ceil(extract(epoch from (l.starts_at - now())))::int, 0) as starts_in,
         coalesce(nullif(p.display_name, ''), 'Your partner') as locker_name,
         l.message as note, l.strict as is_strict, l.together as is_together, l.extended_seconds as extra,
         (l.unlock_asked_at is not null and l.unlock_answer is null) as is_asked,
         coalesce(l.unlock_answer, '') as reply,
         case when l.unlock_answer = 'denied' and l.unlock_answered_at is not null
              then greatest(0, 300 - extract(epoch from (now() - l.unlock_answered_at))::int) else 0 end as retry_in
    into r
    from public.partner_locks l left join public.profiles p on p.id = l.locker
   where l.target = me and l.ended_at is null;
  if found then
    if r.starts_in > 0 then
      return jsonb_build_object('state', 'scheduled', 'id', r.lock_id, 'seconds', r.total, 'starts_in', r.starts_in,
        'by', r.locker_name, 'message', coalesce(r.note, ''), 'strict', r.is_strict, 'together', r.is_together);
    end if;
    return jsonb_build_object('state', 'active', 'id', r.lock_id, 'seconds', r.total,
      'remaining', greatest(r.left_s, 0), 'by', r.locker_name,
      'message', coalesce(r.note, ''), 'strict', r.is_strict, 'together', r.is_together, 'extended', r.extra,
      'asked', r.is_asked, 'answer', r.reply, 'retry_in', r.retry_in);
  end if;
  select l.id as lock_id, l.ended_by as how into r
    from public.partner_locks l
   where l.target = me and l.ended_at is not null and l.ended_at > now() - interval '1 day'
   order by l.id desc limit 1;
  if found then
    return jsonb_build_object('state', 'none', 'last', jsonb_build_object('id', r.lock_id, 'ended_by', r.how));
  end if;
  return jsonb_build_object('state', 'none');
end;
$function$;

create or replace function public.their_lock()
 returns jsonb language plpgsql security definer set search_path to '' as $function$
declare
  me uuid := auth.uid();
  r record;
  v_other uuid;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  perform public._lock_tidy(me);
  select pr.other into v_other from public._pair_of(me) pr;
  if v_other is not null then perform public._fire_due(v_other); end if;
  select l.id as lock_id, l.seconds as total, ceil(extract(epoch from (l.ends_at - now())))::int as remaining,
         greatest(ceil(extract(epoch from (l.starts_at - now())))::int, 0) as starts_in,
         l.message as note, l.strict as is_strict, l.together as is_together, l.pickups as picks, l.extended_seconds as extra,
         (l.unlock_asked_at is not null and l.unlock_answer is null) as is_asked,
         l.unlock_reason as reason, coalesce(l.unlock_answer, '') as reply,
         case when l.last_beat is null then -1 else extract(epoch from (now() - l.last_beat))::int end as beat_ago,
         l.escapes as esc
    into r from public.partner_locks l where l.locker = me and l.ended_at is null;
  if found then
    return jsonb_build_object('state', 'active', 'id', r.lock_id, 'seconds', r.total, 'remaining', greatest(r.remaining, 0),
      'starts_in', r.starts_in,
      'message', coalesce(r.note, ''), 'strict', r.is_strict, 'together', r.is_together, 'pickups', r.picks,
      'extended', r.extra, 'asked', r.is_asked, 'reason', coalesce(r.reason, ''), 'answer', r.reply,
      'beat_ago', r.beat_ago, 'escapes', r.esc);
  end if;
  return jsonb_build_object('state', 'none');
end;
$function$;

revoke all on function public._fire_due(uuid) from public, anon, authenticated;
revoke all on function public._fire_due_all() from public, anon, authenticated;
revoke all on function public.schedule_add(int, int, int, text, text, boolean) from public, anon;
revoke all on function public.schedule_remove(bigint) from public, anon;
revoke all on function public.lock_schedules() from public, anon;
grant execute on function public.schedule_add(int, int, int, text, text, boolean) to authenticated;
grant execute on function public.schedule_remove(bigint) to authenticated;
grant execute on function public.lock_schedules() to authenticated;

-- Every minute, start what is due (replacing any earlier copy of the job).
select cron.unschedule(jobid) from cron.job where jobname = 'lockin-repeating-locks';
select cron.schedule('lockin-repeating-locks', '* * * * *', 'select public._fire_due_all()');
