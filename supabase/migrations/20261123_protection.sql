-- Closing the ways out of a pairing (0.21.0).
--
--   * delete_my_account() used to delete the person's user (and with it the lock and the partnership) with no
--     questions asked. Now it is refused while a lock is on them (running or scheduled: 'locked_now') and while
--     they are paired ('paired': unpairing already needs the partner's agreement, so that is the way out).
--   * protect_requests: the phone asks its partner before the protection comes off (switching off device admin,
--     uninstalling, turning off "display over other apps" or app-time access, switching off the guard, signing
--     out). The partner answers Allow or Not yet. Allow opens a 10 minute window; the phone tracks that window on
--     its own clock. Same shape as unpair_asked / unpair_answer. Only these functions touch the table.
--   * lock_tamper() accepts more kinds: usage_off, guard_off, silent.
--   * _silent_check(): pg_cron, every minute, writes a "silent" note into the chat of a running lock whose phone
--     has not checked in for 4 minutes (LockIn stopped, no internet to LockIn, phone off). Once per lock.

-- ---------------------------------------------------------------------------------------------
-- Account deletion
-- ---------------------------------------------------------------------------------------------
create or replace function public.delete_my_account()
returns void
language plpgsql
security definer
set search_path = public, auth
as $$
declare uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'not_signed_in';
  end if;
  perform public._lock_tidy(uid);
  if exists (select 1 from public.partner_locks where target = uid and ended_at is null) then
    raise exception 'locked_now';
  end if;
  if public._partner_of(uid) is not null then
    raise exception 'paired';
  end if;
  delete from auth.users where id = uid;
end;
$$;

revoke all on function public.delete_my_account() from public, anon;
grant execute on function public.delete_my_account() to authenticated;

-- ---------------------------------------------------------------------------------------------
-- Protection requests
-- ---------------------------------------------------------------------------------------------
create table if not exists public.protect_requests (
  id bigserial primary key,
  partnership_id uuid not null references public.partnerships(id) on delete cascade,
  requester uuid not null references auth.users(id) on delete cascade,
  kind text not null check (kind in ('admin', 'uninstall', 'overlay', 'usage', 'guard', 'signout', 'other')),
  status text not null default 'open' check (status in ('open', 'allowed', 'declined')),
  created_at timestamptz not null default now(),
  answered_at timestamptz,
  allowed_until timestamptz
);
create index if not exists protect_requests_pid on public.protect_requests (partnership_id, created_at desc);
alter table public.protect_requests enable row level security;
revoke all on public.protect_requests from anon, authenticated;
revoke all on sequence public.protect_requests_id_seq from anon, authenticated;

-- I ask my partner to allow [p_kind]. Returns {state: none | open | allowed | declined, id, left, note}.
--   none      I'm not paired: there is nobody to ask, so nothing is held back.
--   allowed   an earlier Allow is still running (left = seconds).
--   declined  they said not yet a moment ago (left = seconds until I may ask again).
create or replace function public.protect_ask(p_kind text)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid; k text := coalesce(nullif(btrim(p_kind), ''), 'other'); r public.protect_requests; mid bigint;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  if k not in ('admin', 'uninstall', 'overlay', 'usage', 'guard', 'signout', 'other') then k := 'other'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_pid is null then return jsonb_build_object('state', 'none'); end if;

  select * into r from public.protect_requests
   where requester = me and partnership_id = v_pid and kind = k and status = 'allowed' and allowed_until > now()
   order by id desc limit 1;
  if found then
    return jsonb_build_object('state', 'allowed', 'id', r.id, 'left', ceil(extract(epoch from (r.allowed_until - now())))::int);
  end if;

  select * into r from public.protect_requests
   where requester = me and partnership_id = v_pid and kind = k and status = 'declined' and answered_at > now() - interval '5 minutes'
   order by id desc limit 1;
  if found then
    return jsonb_build_object('state', 'declined', 'id', r.id, 'left', ceil(extract(epoch from (r.answered_at + interval '5 minutes' - now())))::int);
  end if;

  select * into r from public.protect_requests
   where requester = me and partnership_id = v_pid and kind = k and status = 'open' and created_at > now() - interval '30 minutes'
   order by id desc limit 1;
  if found then
    return jsonb_build_object('state', 'open', 'id', r.id);
  end if;

  insert into public.protect_requests (partnership_id, requester, kind) values (v_pid, me, k) returning * into r;
  insert into public.messages (partnership_id, sender, kind, body) values (v_pid, me, 'event', 'protect_asked|' || k) returning id into mid;
  return jsonb_build_object('state', 'open', 'id', r.id, 'note', mid);
end $$;

-- Where my request stands: open | allowed (left = seconds) | declined | expired.
create or replace function public.protect_status(p_id bigint)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); r public.protect_requests;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select * into r from public.protect_requests where id = p_id and requester = me;
  if not found then return jsonb_build_object('state', 'expired'); end if;
  if r.status = 'allowed' then
    if r.allowed_until > now() then
      return jsonb_build_object('state', 'allowed', 'left', ceil(extract(epoch from (r.allowed_until - now())))::int);
    end if;
    return jsonb_build_object('state', 'expired');
  end if;
  if r.status = 'open' and r.created_at < now() - interval '30 minutes' then
    return jsonb_build_object('state', 'expired');
  end if;
  return jsonb_build_object('state', r.status);
end $$;

-- I (the partner) answer the newest open request. Returns the chat note's id (for the push).
create or replace function public.protect_answer(p_allow boolean)
returns bigint language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid; r public.protect_requests; mid bigint;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_pid is null then raise exception 'no_request'; end if;
  select * into r from public.protect_requests
   where partnership_id = v_pid and requester = v_other and status = 'open' and created_at > now() - interval '30 minutes'
   order by id desc limit 1;
  if not found then raise exception 'no_request'; end if;
  if p_allow then
    update public.protect_requests set status = 'allowed', answered_at = now(), allowed_until = now() + interval '10 minutes' where id = r.id;
    insert into public.messages (partnership_id, sender, kind, body) values (v_pid, me, 'event', 'protect_allowed|' || r.kind) returning id into mid;
  else
    update public.protect_requests set status = 'declined', answered_at = now() where id = r.id;
    insert into public.messages (partnership_id, sender, kind, body) values (v_pid, me, 'event', 'protect_declined|' || r.kind) returning id into mid;
  end if;
  return mid;
end $$;

revoke all on function public.protect_ask(text) from public, anon;
revoke all on function public.protect_status(bigint) from public, anon;
revoke all on function public.protect_answer(boolean) from public, anon;
grant execute on function public.protect_ask(text) to authenticated;
grant execute on function public.protect_status(bigint) to authenticated;
grant execute on function public.protect_answer(boolean) to authenticated;

-- ---------------------------------------------------------------------------------------------
-- More warnings from a locked phone
-- ---------------------------------------------------------------------------------------------
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
  insert into public.messages (partnership_id, sender, kind, body)
  values (pid, me, 'event', 'lock_tamper|' || k || case when d <> '' then '|' || d else '' end)
  returning id into mid;
  return mid;
end $$;

-- ---------------------------------------------------------------------------------------------
-- A phone that went quiet during a lock
-- ---------------------------------------------------------------------------------------------
create or replace function public._silent_check()
returns void language plpgsql security definer set search_path to '' as $$
declare r record; v_pid uuid; v_min int;
begin
  for r in
    select l.id, l.target, l.starts_at, coalesce(l.last_beat, l.starts_at) as seen
      from public.partner_locks l
     where l.ended_at is null and l.starts_at <= now() and l.ends_at > now()
       and coalesce(l.last_beat, l.starts_at) < now() - interval '4 minutes'
  loop
    select pr.pid into v_pid from public._pair_of(r.target) pr;
    if v_pid is null then continue; end if;
    if exists (select 1 from public.messages m
                where m.partnership_id = v_pid and m.sender = r.target and m.kind = 'event'
                  and m.body like 'lock\_tamper|silent%' and m.created_at >= r.starts_at) then
      continue;
    end if;
    v_min := greatest(1, floor(extract(epoch from (now() - r.seen)) / 60))::int;
    insert into public.messages (partnership_id, sender, kind, body)
    values (v_pid, r.target, 'event', 'lock_tamper|silent|' || v_min);
  end loop;
end $$;
revoke all on function public._silent_check() from public, anon, authenticated;

create extension if not exists pg_cron;
select cron.unschedule(jobid) from cron.job where jobname = 'lockin-silent-check';
select cron.schedule('lockin-silent-check', '* * * * *', 'select public._silent_check()');
