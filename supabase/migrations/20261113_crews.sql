-- Crews: 3 to 8 friends on one rope, alongside your partner.
--
-- - Make a crew (you get a 6-letter code) or join one with a code. One crew at a time, up to 8 people.
-- - Crew chat. Each message rings with its sender's own message sound (profiles.message_sound).
-- - Crew sessions: anyone starts one (now or later, 5 min to 8 h). Whoever taps "I'm in" is locked for it on
--   their own phone. Leaving once it has begun counts as leaving early, and the crew sees it. Nobody can lock
--   anyone else: joining is always your own choice.
-- - The board: hours each person held in crew sessions this week (from Monday).
--
-- Nothing here touches the partner tables or functions. The phone holds a crew lock with the same lock
-- service as a partner lock and checks in with crew_beat() instead of lock_beat().
--
-- RLS is on with no policies: everything goes through these SECURITY DEFINER functions.

create table if not exists public.crews (
  id uuid primary key default gen_random_uuid(),
  name text not null check (char_length(name) between 1 and 40),
  code text not null unique,
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now()
);

create table if not exists public.crew_members (
  crew_id uuid not null references public.crews(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  joined_at timestamptz not null default now(),
  primary key (crew_id, user_id)
);
-- One crew per person.
create unique index if not exists crew_members_one_crew on public.crew_members(user_id);

create table if not exists public.crew_messages (
  id bigint generated always as identity primary key,
  crew_id uuid not null references public.crews(id) on delete cascade,
  sender uuid references auth.users(id) on delete set null,
  kind text not null check (kind in ('text', 'event')),
  body text not null check (char_length(body) between 1 and 2000),
  created_at timestamptz not null default now(),
  -- Replies and voice messages (see 20261113_replies_voice.sql for the bucket).
  reply_to bigint references public.crew_messages(id) on delete set null,
  voice text check (voice is null or char_length(voice) < 200),
  voice_ms int check (voice_ms is null or voice_ms between 0 and 300000),
  voice_wave text check (voice_wave is null or voice_wave ~ '^[0-9]{0,64}$')
);
create index if not exists crew_messages_crew on public.crew_messages(crew_id, id);

create table if not exists public.crew_sessions (
  id bigint generated always as identity primary key,
  crew_id uuid not null references public.crews(id) on delete cascade,
  started_by uuid references auth.users(id) on delete set null,
  seconds int not null check (seconds between 300 and 28800),
  starts_at timestamptz not null,
  ends_at timestamptz not null,
  canceled_at timestamptz,
  created_at timestamptz not null default now()
);
create index if not exists crew_sessions_crew on public.crew_sessions(crew_id, ends_at desc);

create table if not exists public.crew_session_members (
  session_id bigint not null references public.crew_sessions(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  joined_at timestamptz not null default now(),
  left_at timestamptz,
  primary key (session_id, user_id)
);

alter table public.crews enable row level security;
alter table public.crew_members enable row level security;
alter table public.crew_messages enable row level security;
alter table public.crew_sessions enable row level security;
alter table public.crew_session_members enable row level security;

-- ---- Helpers ---------------------------------------------------------------------------------

create or replace function public._crew_of(p_user uuid)
returns uuid language sql stable security definer set search_path to '' as $$
  select crew_id from public.crew_members where user_id = p_user;
$$;

create or replace function public._crew_name_of(p_user uuid)
returns text language sql stable security definer set search_path to '' as $$
  select coalesce(nullif(btrim(display_name), ''), 'Someone') from public.profiles where id = p_user;
$$;

-- The crew's session that hasn't finished yet (upcoming or running), if any.
create or replace function public._crew_live_session(p_crew uuid)
returns bigint language sql stable security definer set search_path to '' as $$
  select id from public.crew_sessions
   where crew_id = p_crew and canceled_at is null and ends_at > now()
   order by starts_at limit 1;
$$;

-- Seconds a member actually held in a session, up to now.
create or replace function public._crew_held(s public.crew_sessions, m public.crew_session_members)
returns int language sql stable set search_path to '' as $$
  select greatest(0, extract(epoch from (
    least(coalesce(m.left_at, 'infinity'::timestamptz), s.ends_at, now(), coalesce(s.canceled_at, 'infinity'::timestamptz))
    - greatest(s.starts_at, m.joined_at)
  )))::int;
$$;

create or replace function public._crew_event(p_crew uuid, p_sender uuid, p_body text)
returns bigint language sql security definer set search_path to '' as $$
  insert into public.crew_messages (crew_id, sender, kind, body) values (p_crew, p_sender, 'event', p_body) returning id;
$$;

-- ---- Joining and leaving ---------------------------------------------------------------------

create or replace function public.crew_create(p_name text)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_name text := left(btrim(coalesce(p_name, '')), 40); v_code text; v_id uuid;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  if public._crew_of(me) is not null then raise exception 'already_in_crew'; end if;
  if v_name = '' then raise exception 'crew_name_needed'; end if;
  for i in 1..20 loop
    -- No 0/O or 1/I, so a code read out loud can't be misheard.
    select string_agg(substr('ABCDEFGHJKLMNPQRSTUVWXYZ23456789', 1 + floor(random() * 32)::int, 1), '')
      into v_code from generate_series(1, 6);
    exit when not exists (select 1 from public.crews where code = v_code);
  end loop;
  insert into public.crews (name, code, created_by) values (v_name, v_code, me) returning id into v_id;
  insert into public.crew_members (crew_id, user_id) values (v_id, me);
  perform public._crew_event(v_id, me, 'crew_made|' || v_name);
  return jsonb_build_object('id', v_id, 'code', v_code);
end $$;

create or replace function public.crew_join(p_code text)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_id uuid; v_count int;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  if public._crew_of(me) is not null then raise exception 'already_in_crew'; end if;
  select id into v_id from public.crews where code = upper(btrim(coalesce(p_code, '')));
  if v_id is null then raise exception 'crew_not_found'; end if;
  select count(*) into v_count from public.crew_members where crew_id = v_id;
  if v_count >= 8 then raise exception 'crew_full'; end if;
  insert into public.crew_members (crew_id, user_id) values (v_id, me);
  return jsonb_build_object('id', v_id, 'message', public._crew_event(v_id, me, 'crew_joined'));
end $$;

create or replace function public.crew_leave()
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_crew uuid; v_live bigint;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_crew := public._crew_of(me);
  if v_crew is null then return jsonb_build_object('left', false); end if;
  -- Leaving the crew mid-session counts as leaving the session early.
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
  end if;
  return jsonb_build_object('left', true);
end $$;

-- ---- What the Crew tab shows -----------------------------------------------------------------

create or replace function public.crew_state()
returns jsonb language plpgsql security definer set search_path to '' as $$
declare
  me uuid := auth.uid(); v_crew uuid; c record; s public.crew_sessions; v_live bigint;
  v_week timestamptz := date_trunc('week', now()); v_session jsonb := null; v_members jsonb; v_recent jsonb;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_crew := public._crew_of(me);
  if v_crew is null then return jsonb_build_object('crew', null); end if;
  select * into c from public.crews where id = v_crew;

  v_live := public._crew_live_session(v_crew);
  if v_live is not null then
    select * into s from public.crew_sessions where id = v_live;
    v_session := jsonb_build_object(
      'id', s.id,
      'by', s.started_by,
      'by_name', public._crew_name_of(s.started_by),
      'seconds', s.seconds,
      'starts_in', greatest(0, ceil(extract(epoch from (s.starts_at - now())))::int),
      'remaining', greatest(0, ceil(extract(epoch from (s.ends_at - greatest(now(), s.starts_at))))::int),
      'starts_at', extract(epoch from s.starts_at)::bigint,
      'joined', coalesce((select jsonb_agg(user_id) from public.crew_session_members where session_id = s.id and left_at is null), '[]'::jsonb),
      'left', coalesce((select jsonb_agg(user_id) from public.crew_session_members where session_id = s.id and left_at is not null), '[]'::jsonb)
    );
  end if;

  select coalesce(jsonb_agg(x order by (x->>'week')::int desc, x->>'name'), '[]'::jsonb) into v_members from (
    select jsonb_build_object(
      'id', m.user_id,
      'name', public._crew_name_of(m.user_id),
      'sound', coalesce((select message_sound from public.profiles where id = m.user_id), 'message'),
      'week', coalesce((
        select sum(public._crew_held(ss, sm))::int
          from public.crew_session_members sm join public.crew_sessions ss on ss.id = sm.session_id
         where sm.user_id = m.user_id and ss.crew_id = v_crew and ss.ends_at > v_week and ss.starts_at < now()
      ), 0),
      'early', coalesce((
        select count(*)::int
          from public.crew_session_members sm join public.crew_sessions ss on ss.id = sm.session_id
         where sm.user_id = m.user_id and ss.crew_id = v_crew and ss.ends_at > v_week
           and sm.left_at is not null and sm.left_at < ss.ends_at
      ), 0),
      'joined_at', extract(epoch from m.joined_at)::bigint
    ) as x
    from public.crew_members m where m.crew_id = v_crew
  ) q;

  select jsonb_build_object('name', public._crew_name_of(sender), 'body', body, 'at', extract(epoch from created_at)::bigint, 'mine', sender = me)
    into v_recent
    from public.crew_messages where crew_id = v_crew and kind = 'text' order by id desc limit 1;

  return jsonb_build_object(
    'crew', jsonb_build_object('id', c.id, 'name', c.name, 'code', c.code, 'mine', c.created_by = me,
                               'created_at', extract(epoch from c.created_at)::bigint),
    'me', me,
    'members', v_members,
    'session', v_session,
    'recent', v_recent,
    'last', coalesce((select max(id) from public.crew_messages where crew_id = v_crew), 0)
  );
end $$;

-- ---- Chat ------------------------------------------------------------------------------------

-- A chat line, optionally answering [p_reply] and/or carrying a voice recording (already uploaded to
-- voice/c-<crew id>/...).
create or replace function public.crew_send(
  p_body text, p_reply bigint default null, p_voice text default null, p_voice_ms int default null, p_voice_wave text default null
)
returns bigint language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_crew uuid; v_body text := btrim(coalesce(p_body, '')); v_id bigint; v_reply bigint := null;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_crew := public._crew_of(me);
  if v_crew is null then raise exception 'no_crew'; end if;
  if p_voice is not null then
    if p_voice not like 'c-' || v_crew::text || '/%' then raise exception 'bad_voice'; end if;
    if v_body = '' then v_body := 'Voice message'; end if;
  end if;
  if v_body = '' then raise exception 'empty_message'; end if;
  if p_reply is not null then
    select id into v_reply from public.crew_messages where id = p_reply and crew_id = v_crew;
  end if;
  insert into public.crew_messages (crew_id, sender, kind, body, reply_to, voice, voice_ms, voice_wave)
  values (v_crew, me, 'text', left(v_body, 2000), v_reply, p_voice,
          case when p_voice is null then null else least(greatest(coalesce(p_voice_ms, 0), 0), 300000) end,
          case when p_voice is null then null else left(regexp_replace(coalesce(p_voice_wave, ''), '[^0-9]', '', 'g'), 64) end)
  returning id into v_id;
  return v_id;
end $$;

-- Messages after [p_after], oldest first. With 0, the latest [p_limit].
create or replace function public.crew_messages(p_after bigint default 0, p_limit int default 60)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_crew uuid; v_lim int := least(greatest(coalesce(p_limit, 60), 1), 200);
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_crew := public._crew_of(me);
  if v_crew is null then return '[]'::jsonb; end if;
  return coalesce((
    select jsonb_agg(x order by (x->>'id')::bigint) from (
      select jsonb_build_object('id', m.id, 'sender', m.sender, 'name', public._crew_name_of(m.sender),
                                'kind', m.kind, 'body', m.body, 'at', extract(epoch from m.created_at)::bigint,
                                'mine', m.sender = me, 'reply_to', m.reply_to, 'voice', m.voice,
                                'voice_ms', m.voice_ms, 'voice_wave', m.voice_wave) as x
        from public.crew_messages m
       where m.crew_id = v_crew and m.id > coalesce(p_after, 0)
       order by m.id desc limit v_lim
    ) q
  ), '[]'::jsonb);
end $$;

-- ---- Sessions --------------------------------------------------------------------------------

create or replace function public.crew_session_start(p_minutes int, p_start_in int default 0)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_crew uuid; v_start timestamptz; v_id bigint; v_msg bigint;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_crew := public._crew_of(me);
  if v_crew is null then raise exception 'no_crew'; end if;
  if coalesce(p_minutes, 0) not between 5 and 480 then raise exception 'bad_lock'; end if;
  if coalesce(p_start_in, 0) not between 0 and 360 then raise exception 'bad_start'; end if;
  if public._crew_live_session(v_crew) is not null then raise exception 'session_running'; end if;
  v_start := now() + make_interval(mins => coalesce(p_start_in, 0));
  insert into public.crew_sessions (crew_id, started_by, seconds, starts_at, ends_at)
  values (v_crew, me, p_minutes * 60, v_start, v_start + make_interval(mins => p_minutes))
  returning id into v_id;
  insert into public.crew_session_members (session_id, user_id) values (v_id, me);
  v_msg := public._crew_event(v_crew, me, 'session_start|' || p_minutes || '|' || extract(epoch from v_start)::bigint);
  return jsonb_build_object('session', v_id, 'message', v_msg);
end $$;

create or replace function public.crew_session_join(p_session bigint)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_crew uuid; s public.crew_sessions; m public.crew_session_members;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_crew := public._crew_of(me);
  select * into s from public.crew_sessions where id = p_session;
  if s.id is null or s.crew_id is distinct from v_crew then raise exception 'session_not_found'; end if;
  if s.canceled_at is not null or s.ends_at <= now() then raise exception 'session_over'; end if;
  select * into m from public.crew_session_members where session_id = s.id and user_id = me;
  if m.user_id is not null and m.left_at is not null then raise exception 'already_left'; end if;
  if m.user_id is null then
    insert into public.crew_session_members (session_id, user_id) values (s.id, me);
    perform public._crew_event(v_crew, me, 'session_join');
  end if;
  return jsonb_build_object('session', s.id,
    'remaining', greatest(0, ceil(extract(epoch from (s.ends_at - greatest(now(), s.starts_at))))::int),
    'starts_in', greatest(0, ceil(extract(epoch from (s.starts_at - now())))::int));
end $$;

-- Before it begins: just steps out (and the starter stepping out with nobody else in cancels it).
-- Once it has begun: leaves early, which the crew sees.
create or replace function public.crew_session_leave(p_session bigint)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_crew uuid; s public.crew_sessions; v_msg bigint := null;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_crew := public._crew_of(me);
  select * into s from public.crew_sessions where id = p_session;
  if s.id is null or s.crew_id is distinct from v_crew then raise exception 'session_not_found'; end if;
  if s.canceled_at is not null or s.ends_at <= now() then return jsonb_build_object('left', false); end if;
  if s.starts_at > now() then
    delete from public.crew_session_members where session_id = s.id and user_id = me;
    if not exists (select 1 from public.crew_session_members where session_id = s.id) then
      update public.crew_sessions set canceled_at = now() where id = s.id;
      v_msg := public._crew_event(v_crew, me, 'session_canceled');
    end if;
    return jsonb_build_object('left', true, 'early', false, 'message', v_msg);
  end if;
  update public.crew_session_members set left_at = now() where session_id = s.id and user_id = me and left_at is null;
  if found then
    v_msg := public._crew_event(v_crew, me, 'session_left|' ||
      greatest(1, ceil(extract(epoch from (s.ends_at - now())) / 60))::int);
  end if;
  return jsonb_build_object('left', true, 'early', true, 'message', v_msg);
end $$;

-- The phone's lock service checks in here every 15 s during a crew lock: is my crew session still on?
create or replace function public.crew_beat()
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_crew uuid; s public.crew_sessions;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_crew := public._crew_of(me);
  if v_crew is null then return jsonb_build_object('state', 'none'); end if;
  select ss.* into s from public.crew_sessions ss
    join public.crew_session_members sm on sm.session_id = ss.id and sm.user_id = me and sm.left_at is null
   where ss.crew_id = v_crew and ss.canceled_at is null and ss.ends_at > now()
   order by ss.starts_at limit 1;
  if s.id is null then return jsonb_build_object('state', 'none'); end if;
  if s.starts_at > now() then
    return jsonb_build_object('state', 'upcoming', 'id', s.id, 'starts_in', ceil(extract(epoch from (s.starts_at - now())))::int,
      'seconds', s.seconds, 'crew', (select name from public.crews where id = v_crew));
  end if;
  return jsonb_build_object('state', 'active', 'id', s.id,
    'remaining', greatest(0, ceil(extract(epoch from (s.ends_at - now())))::int),
    'crew', (select name from public.crews where id = v_crew));
end $$;

-- ---- Access ----------------------------------------------------------------------------------

do $$
declare f text;
begin
  foreach f in array array[
    'public._crew_of(uuid)', 'public._crew_name_of(uuid)', 'public._crew_live_session(uuid)',
    'public._crew_held(public.crew_sessions, public.crew_session_members)', 'public._crew_event(uuid, uuid, text)'
  ] loop
    execute format('revoke all on function %s from public, anon, authenticated', f);
  end loop;
  foreach f in array array[
    'public.crew_create(text)', 'public.crew_join(text)', 'public.crew_leave()', 'public.crew_state()',
    'public.crew_send(text, bigint, text, int, text)', 'public.crew_messages(bigint, int)', 'public.crew_session_start(int, int)',
    'public.crew_session_join(bigint)', 'public.crew_session_leave(bigint)', 'public.crew_beat()'
  ] loop
    execute format('revoke all on function %s from public, anon', f);
    execute format('grant execute on function %s to authenticated', f);
  end loop;
end $$;
