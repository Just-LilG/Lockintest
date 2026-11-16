-- Crew goal, standing sessions, pokes and cheers, crew streak and the weekly recap.
--
-- - The crew picks a weekly goal (minutes held in crew sessions, all members together). The Crew tab shows the
--   climb towards it; when the crew reaches it, one "goal_hit" line goes in the chat.
-- - Standing sessions: weekly slots ("Mon 20:00 for 45 min") any member can add or remove (6 at most). They are
--   kept in UTC minutes since Monday 00:00 UTC; each phone shows them in its own time and sets a reminder.
-- - Poke: while a session runs, nudge a member who hasn't joined. Cheer: send a member a cheer, or "get back on"
--   after they left early. Both are quiet chat lines and a push to that one person. Rate limited.
-- - Crew streak: consecutive days (UTC) on which at least two members held 10+ minutes in a crew session.
-- - Last week: the crew's total, the top climber and the number of sessions.
--
-- crew_state() keeps its old answer and gains goal_min, week_total, streak, last_week and standing.
-- RLS is on with no policies: everything goes through these SECURITY DEFINER functions.

alter table public.crews add column if not exists weekly_goal_min int not null default 0
  check (weekly_goal_min between 0 and 6000);

create table if not exists public.crew_standing (
  id bigint generated always as identity primary key,
  crew_id uuid not null references public.crews(id) on delete cascade,
  wk_minute int not null check (wk_minute between 0 and 10079),
  minutes int not null check (minutes between 5 and 480),
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now()
);
create index if not exists crew_standing_crew on public.crew_standing(crew_id);
alter table public.crew_standing enable row level security;

-- Seconds all members held in crew sessions that started in [p_from, p_to).
create or replace function public._crew_total(p_crew uuid, p_from timestamptz, p_to timestamptz)
returns int language sql stable security definer set search_path to '' as $$
  select coalesce(sum(public._crew_held(ss, sm)), 0)::int
    from public.crew_sessions ss join public.crew_session_members sm on sm.session_id = ss.id
   where ss.crew_id = p_crew and ss.starts_at >= p_from and ss.starts_at < p_to and ss.starts_at < now();
$$;

create or replace function public._crew_streak(p_crew uuid)
returns int language plpgsql stable security definer set search_path to '' as $$
declare d date := (now() at time zone 'UTC')::date; n int := 0;
begin
  if not public._crew_day_ok(p_crew, d) then d := d - 1; end if;
  while n < 365 and public._crew_day_ok(p_crew, d) loop
    n := n + 1; d := d - 1;
  end loop;
  return n;
end $$;

-- A day counts when at least two members held 10 minutes or more in one crew session that began that day.
create or replace function public._crew_day_ok(p_crew uuid, p_day date)
returns boolean language sql stable security definer set search_path to '' as $$
  select exists (
    select 1 from public.crew_sessions ss
     where ss.crew_id = p_crew and ss.canceled_at is null and ss.starts_at < now()
       and (ss.starts_at at time zone 'UTC')::date = p_day
       and (select count(*) from public.crew_session_members sm
             where sm.session_id = ss.id and public._crew_held(ss, sm) >= 600) >= 2
  );
$$;

-- crew_state keeps its old shape and gains the new keys.
do $$ begin
  if to_regprocedure('public._crew_state_base()') is null then
    alter function public.crew_state() rename to _crew_state_base;
  end if;
end $$;

create or replace function public.crew_state()
returns jsonb language plpgsql security definer set search_path to '' as $$
declare
  me uuid := auth.uid(); v_crew uuid; base jsonb; v_week timestamptz := date_trunc('week', now());
  v_prev timestamptz := date_trunc('week', now()) - interval '7 days'; v_goal int; v_last jsonb;
begin
  base := public._crew_state_base();
  if base->'crew' is null or base->'crew' = 'null'::jsonb then return base; end if;
  v_crew := public._crew_of(me);
  select weekly_goal_min into v_goal from public.crews where id = v_crew;

  select jsonb_build_object(
    'total', public._crew_total(v_crew, v_prev, v_week),
    'sessions', (select count(*)::int from public.crew_sessions where crew_id = v_crew and canceled_at is null and starts_at >= v_prev and starts_at < v_week),
    'top_name', (select public._crew_name_of(q.user_id) from (
        select sm.user_id, sum(public._crew_held(ss, sm)) as held
          from public.crew_sessions ss join public.crew_session_members sm on sm.session_id = ss.id
         where ss.crew_id = v_crew and ss.starts_at >= v_prev and ss.starts_at < v_week
         group by sm.user_id order by held desc limit 1) q),
    'top_seconds', coalesce((select max(held)::int from (
        select sum(public._crew_held(ss, sm)) as held
          from public.crew_sessions ss join public.crew_session_members sm on sm.session_id = ss.id
         where ss.crew_id = v_crew and ss.starts_at >= v_prev and ss.starts_at < v_week
         group by sm.user_id) q2), 0)
  ) into v_last;

  return base || jsonb_build_object(
    'goal_min', v_goal,
    'week_total', public._crew_total(v_crew, v_week, now() + interval '1 day'),
    'week_start', extract(epoch from v_week)::bigint,
    'streak', public._crew_streak(v_crew),
    'last_week', v_last,
    'standing', coalesce((
      select jsonb_agg(jsonb_build_object('id', s.id, 'wk_minute', s.wk_minute, 'minutes', s.minutes,
                                          'by_name', public._crew_name_of(s.created_by)) order by s.wk_minute)
        from public.crew_standing s where s.crew_id = v_crew), '[]'::jsonb)
  );
end $$;

-- ---- Goal ------------------------------------------------------------------------------------

create or replace function public.crew_goal_set(p_minutes int)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_crew uuid; v_msg bigint := null;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_crew := public._crew_of(me);
  if v_crew is null then raise exception 'no_crew'; end if;
  if coalesce(p_minutes, -1) not between 0 and 6000 then raise exception 'bad_goal'; end if;
  update public.crews set weekly_goal_min = p_minutes where id = v_crew;
  v_msg := public._crew_event(v_crew, me, 'goal_set|' || p_minutes);
  return jsonb_build_object('message', v_msg);
end $$;

-- Once per week, when the crew's total reaches the goal: one line in the chat. Safe to call any number of times.
create or replace function public.crew_goal_check()
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_crew uuid; v_goal int; v_week timestamptz := date_trunc('week', now()); v_tag text; v_msg bigint := 0;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_crew := public._crew_of(me);
  if v_crew is null then return jsonb_build_object('message', 0); end if;
  select weekly_goal_min into v_goal from public.crews where id = v_crew;
  if v_goal <= 0 or public._crew_total(v_crew, v_week, now() + interval '1 day') < v_goal * 60 then
    return jsonb_build_object('message', 0);
  end if;
  v_tag := 'goal_hit|' || extract(epoch from v_week)::bigint;
  if exists (select 1 from public.crew_messages where crew_id = v_crew and kind = 'event' and body = v_tag) then
    return jsonb_build_object('message', 0);
  end if;
  v_msg := public._crew_event(v_crew, me, v_tag);
  return jsonb_build_object('message', v_msg);
end $$;

-- ---- Standing sessions -----------------------------------------------------------------------

create or replace function public.crew_standing_add(p_wk_minute int, p_minutes int)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_crew uuid; v_id bigint;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_crew := public._crew_of(me);
  if v_crew is null then raise exception 'no_crew'; end if;
  if coalesce(p_wk_minute, -1) not between 0 and 10079 or coalesce(p_minutes, 0) not between 5 and 480 then raise exception 'bad_lock'; end if;
  if (select count(*) from public.crew_standing where crew_id = v_crew) >= 6 then raise exception 'standing_full'; end if;
  if exists (select 1 from public.crew_standing where crew_id = v_crew and wk_minute = p_wk_minute) then raise exception 'standing_exists'; end if;
  insert into public.crew_standing (crew_id, wk_minute, minutes, created_by) values (v_crew, p_wk_minute, p_minutes, me) returning id into v_id;
  perform public._crew_event(v_crew, me, 'standing_add|' || p_wk_minute || '|' || p_minutes);
  return jsonb_build_object('id', v_id);
end $$;

create or replace function public.crew_standing_remove(p_id bigint)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_crew uuid;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_crew := public._crew_of(me);
  if v_crew is null then raise exception 'no_crew'; end if;
  delete from public.crew_standing where id = p_id and crew_id = v_crew;
  return jsonb_build_object('removed', found);
end $$;

-- ---- Poke and cheer --------------------------------------------------------------------------

create or replace function public.crew_poke(p_user uuid)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_crew uuid; v_live bigint; s public.crew_sessions;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_crew := public._crew_of(me);
  if v_crew is null then raise exception 'no_crew'; end if;
  if p_user is null or p_user = me or public._crew_of(p_user) is distinct from v_crew then raise exception 'not_in_crew'; end if;
  v_live := public._crew_live_session(v_crew);
  if v_live is null then raise exception 'no_session'; end if;
  select * into s from public.crew_sessions where id = v_live;
  if exists (select 1 from public.crew_session_members where session_id = s.id and user_id = p_user and left_at is null) then
    raise exception 'already_in';
  end if;
  if exists (select 1 from public.crew_messages where crew_id = v_crew and kind = 'event' and sender = me
              and body = 'poke|' || p_user and created_at > now() - interval '10 minutes') then
    raise exception 'too_soon';
  end if;
  return jsonb_build_object('message', public._crew_event(v_crew, me, 'poke|' || p_user));
end $$;

create or replace function public.crew_cheer(p_user uuid, p_kind text default 'cheer')
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_crew uuid; v_kind text := case when p_kind = 'back' then 'back' else 'cheer' end;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_crew := public._crew_of(me);
  if v_crew is null then raise exception 'no_crew'; end if;
  if p_user is null or p_user = me or public._crew_of(p_user) is distinct from v_crew then raise exception 'not_in_crew'; end if;
  if exists (select 1 from public.crew_messages where crew_id = v_crew and kind = 'event' and sender = me
              and body like 'cheer|' || p_user || '|%' and created_at > now() - interval '5 minutes') then
    raise exception 'too_soon';
  end if;
  return jsonb_build_object('message', public._crew_event(v_crew, me, 'cheer|' || p_user || '|' || v_kind));
end $$;

-- ---- Access ----------------------------------------------------------------------------------

do $$
declare f text;
begin
  foreach f in array array[
    'public._crew_total(uuid, timestamptz, timestamptz)', 'public._crew_streak(uuid)', 'public._crew_day_ok(uuid, date)',
    'public._crew_state_base()'
  ] loop
    execute format('revoke all on function %s from public, anon, authenticated', f);
  end loop;
  foreach f in array array[
    'public.crew_state()', 'public.crew_goal_set(int)', 'public.crew_goal_check()', 'public.crew_standing_add(int, int)',
    'public.crew_standing_remove(bigint)', 'public.crew_poke(uuid)', 'public.crew_cheer(uuid, text)'
  ] loop
    execute format('revoke all on function %s from public, anon', f);
    execute format('grant execute on function %s to authenticated', f);
  end loop;
end $$;
