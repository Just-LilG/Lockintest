-- Crew management (0.13.0): owner tools, a crew look, a viewer-local week with real per-member numbers.
-- The crew owner is crews.created_by. If the owner leaves, the longest-standing member takes over.

alter table public.crews add column if not exists emoji text not null default '' check (char_length(emoji) <= 8);
alter table public.crews add column if not exists color int not null default 0 check (color between 0 and 7);

-- Seconds a person held in this crew's sessions between two moments (clipped to that window).
create or replace function public._crew_member_held(p_crew uuid, p_user uuid, p_from timestamptz, p_to timestamptz)
returns int language sql stable security definer set search_path = '' as $$
  select coalesce(sum(greatest(0, extract(epoch from (
           least(coalesce(sm.left_at, 'infinity'::timestamptz), ss.ends_at, now(), coalesce(ss.canceled_at, 'infinity'::timestamptz), p_to)
         - greatest(ss.starts_at, sm.joined_at, p_from)))))::int, 0)
    from public.crew_session_members sm join public.crew_sessions ss on ss.id = sm.session_id
   where sm.user_id = p_user and ss.crew_id = p_crew and ss.ends_at > p_from and ss.starts_at < p_to;
$$;

-- Seconds everyone (including people who have since left) held in a window.
create or replace function public._crew_held_all(p_crew uuid, p_from timestamptz, p_to timestamptz)
returns int language sql stable security definer set search_path = '' as $$
  select coalesce(sum(greatest(0, extract(epoch from (
           least(coalesce(sm.left_at, 'infinity'::timestamptz), ss.ends_at, now(), coalesce(ss.canceled_at, 'infinity'::timestamptz), p_to)
         - greatest(ss.starts_at, sm.joined_at, p_from)))))::int, 0)
    from public.crew_session_members sm join public.crew_sessions ss on ss.id = sm.session_id
   where ss.crew_id = p_crew and ss.ends_at > p_from and ss.starts_at < p_to;
$$;

-- The crew's state for a phone whose clock is p_tz minutes east of UTC: "this week" starts on that phone's Monday.
create or replace function public.crew_state_v2(p_tz int default 0)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid(); v_crew uuid; base jsonb; c record;
  v_off interval := make_interval(mins => greatest(-840, least(840, coalesce(p_tz, 0))));
  v_week timestamptz; v_prev timestamptz; v_members jsonb; v_last jsonb; v_top record;
begin
  base := public._crew_state_base();
  if base->'crew' is null or base->'crew' = 'null'::jsonb then return base; end if;
  v_crew := public._crew_of(me);
  v_week := (date_trunc('week', (now() at time zone 'UTC') + v_off) - v_off) at time zone 'UTC';
  v_prev := v_week - interval '7 days';
  select * into c from public.crews where id = v_crew;

  select coalesce(jsonb_agg(x order by (x->>'week')::int desc, x->>'name'), '[]'::jsonb) into v_members from (
    select jsonb_build_object(
      'id', m.user_id,
      'name', public._crew_name_of(m.user_id),
      'sound', coalesce((select message_sound from public.profiles where id = m.user_id), 'message'),
      'week', public._crew_member_held(v_crew, m.user_id, v_week, now() + interval '1 day'),
      'prev', public._crew_member_held(v_crew, m.user_id, v_prev, v_week),
      'early', coalesce((
        select count(*)::int from public.crew_session_members sm join public.crew_sessions ss on ss.id = sm.session_id
         where sm.user_id = m.user_id and ss.crew_id = v_crew and ss.ends_at > v_week
           and sm.left_at is not null and sm.left_at < ss.ends_at), 0),
      'joined_at', extract(epoch from m.joined_at)::bigint,
      'total', public._crew_member_held(v_crew, m.user_id, '-infinity'::timestamptz, now() + interval '1 day'),
      'sessions', (select count(*)::int from public.crew_session_members sm join public.crew_sessions ss on ss.id = sm.session_id
                    where sm.user_id = m.user_id and ss.crew_id = v_crew and ss.starts_at < now() and ss.canceled_at is null),
      'last', (select extract(epoch from max(ss.starts_at))::bigint from public.crew_session_members sm join public.crew_sessions ss on ss.id = sm.session_id
                where sm.user_id = m.user_id and ss.crew_id = v_crew and ss.starts_at < now() and ss.canceled_at is null),
      'days', (select jsonb_agg(public._crew_member_held(v_crew, m.user_id, v_week + make_interval(days => d), v_week + make_interval(days => d + 1)) order by d)
                 from generate_series(0, 6) d)
    ) as x
    from public.crew_members m where m.crew_id = v_crew
  ) q;

  select e->>'name' as name, (e->>'prev')::int as secs into v_top
    from jsonb_array_elements(v_members) e order by (e->>'prev')::int desc limit 1;
  v_last := jsonb_build_object(
    'total', public._crew_held_all(v_crew, v_prev, v_week),
    'sessions', (select count(*)::int from public.crew_sessions where crew_id = v_crew and canceled_at is null and starts_at >= v_prev and starts_at < v_week),
    'top_name', case when coalesce(v_top.secs, 0) > 0 then v_top.name else null end,
    'top_seconds', coalesce(v_top.secs, 0)
  );

  base := jsonb_set(base, '{members}', v_members);
  base := jsonb_set(base, '{crew}', (base->'crew') || jsonb_build_object('emoji', c.emoji, 'color', c.color, 'owner', c.created_by));
  return base || jsonb_build_object(
    'goal_min', c.weekly_goal_min,
    'week_total', public._crew_held_all(v_crew, v_week, now() + interval '1 day'),
    'week_start', extract(epoch from v_week)::bigint,
    'streak', public._crew_streak(v_crew),
    'last_week', v_last,
    'standing', coalesce((
      select jsonb_agg(jsonb_build_object('id', s.id, 'wk_minute', s.wk_minute, 'minutes', s.minutes,
                                          'by_name', public._crew_name_of(s.created_by), 'by', s.created_by) order by s.wk_minute)
        from public.crew_standing s where s.crew_id = v_crew), '[]'::jsonb)
  );
end $$;

-- The old name keeps working for phones that haven't updated: same answer, UTC weeks.
create or replace function public.crew_state()
returns jsonb language sql security definer set search_path = '' as $$ select public.crew_state_v2(0); $$;

-- Rename the crew and set its emoji and colour (owner only). A blank name keeps the old one.
create or replace function public.crew_update(p_name text, p_emoji text, p_color int)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid(); v_crew uuid; v_name text := left(btrim(coalesce(p_name, '')), 40); v_old text; v_msg bigint;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_crew := public._crew_of(me);
  if v_crew is null then raise exception 'no_crew'; end if;
  if not exists (select 1 from public.crews where id = v_crew and created_by = me) then raise exception 'not_owner'; end if;
  select name into v_old from public.crews where id = v_crew;
  update public.crews set
    name = case when v_name = '' then name else v_name end,
    emoji = left(btrim(coalesce(p_emoji, '')), 8),
    color = greatest(0, least(7, coalesce(p_color, 0)))
   where id = v_crew;
  if v_name <> '' and v_name <> v_old then
    v_msg := public._crew_event(v_crew, me, 'crew_renamed|' || v_name);
  end if;
  return jsonb_build_object('message', v_msg);
end $$;

-- Remove someone from the crew (owner only).
create or replace function public.crew_kick(p_user uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid(); v_crew uuid; v_live bigint; v_name text;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_crew := public._crew_of(me);
  if v_crew is null then raise exception 'no_crew'; end if;
  if not exists (select 1 from public.crews where id = v_crew and created_by = me) then raise exception 'not_owner'; end if;
  if p_user = me then raise exception 'cannot_remove_self'; end if;
  if public._crew_of(p_user) is distinct from v_crew then raise exception 'not_in_crew'; end if;
  v_name := public._crew_name_of(p_user);
  v_live := public._crew_live_session(v_crew);
  if v_live is not null then
    update public.crew_session_members set left_at = now()
     where session_id = v_live and user_id = p_user and left_at is null
       and (select starts_at from public.crew_sessions where id = v_live) <= now();
    delete from public.crew_session_members
     where session_id = v_live and user_id = p_user and (select starts_at from public.crew_sessions where id = v_live) > now();
  end if;
  delete from public.crew_members where crew_id = v_crew and user_id = p_user;
  return jsonb_build_object('message', public._crew_event(v_crew, me, 'crew_kicked|' || p_user || '|' || v_name));
end $$;

-- A new invite code; the old one stops working (owner only).
create or replace function public.crew_new_code()
returns jsonb language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid(); v_crew uuid; v_code text;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_crew := public._crew_of(me);
  if v_crew is null then raise exception 'no_crew'; end if;
  if not exists (select 1 from public.crews where id = v_crew and created_by = me) then raise exception 'not_owner'; end if;
  for i in 1..20 loop
    select string_agg(substr('ABCDEFGHJKLMNPQRSTUVWXYZ23456789', 1 + floor(random() * 32)::int, 1), '') into v_code from generate_series(1, 6);
    exit when not exists (select 1 from public.crews where code = v_code);
  end loop;
  update public.crews set code = v_code where id = v_crew;
  perform public._crew_event(v_crew, me, 'crew_code_new');
  return jsonb_build_object('code', v_code);
end $$;

-- Leaving: if the owner goes, the member who has been here longest takes over.
create or replace function public.crew_leave()
returns jsonb language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid(); v_crew uuid; v_live bigint; v_next uuid;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_crew := public._crew_of(me);
  if v_crew is null then return jsonb_build_object('left', false); end if;
  v_live := public._crew_live_session(v_crew);
  if v_live is not null then
    update public.crew_session_members set left_at = now()
     where session_id = v_live and user_id = me and left_at is null
       and (select starts_at from public.crew_sessions where id = v_live) <= now();
    delete from public.crew_session_members
     where session_id = v_live and user_id = me and (select starts_at from public.crew_sessions where id = v_live) > now();
  end if;
  delete from public.crew_members where crew_id = v_crew and user_id = me;
  if not exists (select 1 from public.crew_members where crew_id = v_crew) then
    delete from public.crews where id = v_crew;
  else
    perform public._crew_event(v_crew, me, 'crew_left');
    if exists (select 1 from public.crews where id = v_crew and (created_by = me or created_by is null)) then
      select user_id into v_next from public.crew_members where crew_id = v_crew order by joined_at limit 1;
      update public.crews set created_by = v_next where id = v_crew;
      perform public._crew_event(v_crew, v_next, 'crew_owner|' || public._crew_name_of(v_next));
    end if;
  end if;
  return jsonb_build_object('left', true);
end $$;

-- Only whoever added a standing session, or the crew owner, can remove it.
create or replace function public.crew_standing_remove(p_id bigint)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid(); v_crew uuid; v_by uuid; v_owner uuid;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_crew := public._crew_of(me);
  if v_crew is null then raise exception 'no_crew'; end if;
  select created_by into v_by from public.crew_standing where id = p_id and crew_id = v_crew;
  if not found then return jsonb_build_object('removed', false); end if;
  select created_by into v_owner from public.crews where id = v_crew;
  if v_by is not null and v_by <> me and v_owner is distinct from me then raise exception 'not_allowed'; end if;
  delete from public.crew_standing where id = p_id and crew_id = v_crew;
  return jsonb_build_object('removed', true);
end $$;

revoke all on function public._crew_member_held(uuid, uuid, timestamptz, timestamptz) from public, anon, authenticated;
revoke all on function public._crew_held_all(uuid, timestamptz, timestamptz) from public, anon, authenticated;
revoke all on function public.crew_state_v2(int) from public, anon;
revoke all on function public.crew_update(text, text, int) from public, anon;
revoke all on function public.crew_kick(uuid) from public, anon;
revoke all on function public.crew_new_code() from public, anon;
grant execute on function public.crew_state_v2(int) to authenticated;
grant execute on function public.crew_update(text, text, int) to authenticated;
grant execute on function public.crew_kick(uuid) to authenticated;
grant execute on function public.crew_new_code() to authenticated;
