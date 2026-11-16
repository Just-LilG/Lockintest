-- Unpairing needs your partner's agreement, so it can't be used to slip out of a lock.
--   * Cancelling an invite nobody has joined yet is still instant.
--   * You can't ask while a lock is on you (running or scheduled).
--   * While your request is open, your partner can't start new locks on you.
--   * If your partner never answers, the request goes through after 7 days, so nobody is held in a pairing.
-- Also: lock_tamper() so the locked phone's warnings reach the locker (the messages policy only lets the app
-- insert a few event kinds itself), and TRUNCATE revoked from signed-in users.
alter table public.partnerships add column if not exists unpair_by uuid;
alter table public.partnerships add column if not exists unpair_at timestamptz;

create or replace function public._unpair(p_pid uuid)
returns void language plpgsql security definer set search_path to '' as $$
declare a uuid; b uuid;
begin
  select owner, partner into a, b from public.partnerships where id = p_pid;
  if a is null then return; end if;
  update public.partner_locks set ended_at = now(), ended_by = 'unpaired'
   where ended_at is null and ((locker = a and target = b) or (locker = b and target = a));
  delete from public.partnerships where id = p_pid;
end $$;

create or replace function public._unpair_due(p_user uuid)
returns void language plpgsql security definer set search_path to '' as $$
declare p record;
begin
  select id, unpair_by, unpair_at into p from public.partnerships
   where (owner = p_user or partner = p_user) and unpair_by is not null and unpair_at < now() - interval '7 days';
  if not found then return; end if;
  perform public._lock_tidy(p.unpair_by);
  if exists (select 1 from public.partner_locks where target = p.unpair_by and ended_at is null) then return; end if;
  perform public._unpair(p.id);
end $$;

-- It used to return nothing; the return type changes, so it is dropped first.
drop function if exists public.leave_partnership();
create function public.leave_partnership()
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); p public.partnerships; other uuid; mid bigint;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select * into p from public.partnerships where owner = me or partner = me limit 1;
  if not found then return jsonb_build_object('state', 'none'); end if;
  if p.partner is null then
    delete from public.partnerships where id = p.id;
    return jsonb_build_object('state', 'none');
  end if;
  other := case when p.owner = me then p.partner else p.owner end;
  if p.unpair_by = other then
    perform public._unpair(p.id);
    return jsonb_build_object('state', 'none');
  end if;
  if p.unpair_by = me then
    return jsonb_build_object('state', 'requested', 'at', extract(epoch from p.unpair_at)::bigint);
  end if;
  perform public._lock_tidy(me);
  if exists (select 1 from public.partner_locks where target = me and ended_at is null) then
    raise exception 'locked_now';
  end if;
  update public.partnerships set unpair_by = me, unpair_at = now() where id = p.id;
  insert into public.messages (partnership_id, sender, kind, body) values (p.id, me, 'event', 'unpair_asked') returning id into mid;
  return jsonb_build_object('state', 'requested', 'at', extract(epoch from now())::bigint, 'note', mid);
end $$;

create or replace function public.unpair_answer(p_agree boolean)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); p public.partnerships; mid bigint;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select * into p from public.partnerships where (owner = me or partner = me) and partner is not null limit 1;
  if not found or p.unpair_by is null or p.unpair_by = me then raise exception 'no_request'; end if;
  if p_agree then
    perform public._unpair(p.id);
    return jsonb_build_object('state', 'none');
  end if;
  update public.partnerships set unpair_by = null, unpair_at = null where id = p.id;
  insert into public.messages (partnership_id, sender, kind, body) values (p.id, me, 'event', 'unpair_declined') returning id into mid;
  return jsonb_build_object('state', 'kept', 'note', mid);
end $$;

create or replace function public.unpair_cancel()
returns void language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid();
begin
  if me is null then raise exception 'not_signed_in'; end if;
  update public.partnerships set unpair_by = null, unpair_at = null where (owner = me or partner = me) and unpair_by = me;
end $$;

create or replace function public.my_partnership()
 returns jsonb language plpgsql security definer set search_path to '' as $function$
declare
  me uuid := auth.uid();
  p public.partnerships;
  other uuid;
  other_name text;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  perform public._unpair_due(me);
  select * into p from public.partnerships where owner = me or partner = me limit 1;
  if not found then
    return jsonb_build_object('status', 'none');
  end if;
  if p.partner is null then
    if p.code_expires is not null and p.code_expires < now() then
      return jsonb_build_object('status', 'none');
    end if;
    return jsonb_build_object('status', 'pending', 'id', p.id, 'code', p.code, 'expires', p.code_expires);
  end if;
  other := case when p.owner = me then p.partner else p.owner end;
  select coalesce(nullif(display_name, ''), 'Your partner') into other_name from public.profiles where id = other;
  return jsonb_build_object('status', 'paired', 'id', p.id, 'partner_id', other,
                            'partner_name', coalesce(other_name, 'Your partner'), 'me', me,
                            'paired_at', coalesce(p.paired_at, p.created_at),
                            'unpair_by', case when p.unpair_by is null then '' when p.unpair_by = me then 'me' else 'them' end,
                            'unpair_due', case when p.unpair_at is null then 0 else extract(epoch from p.unpair_at + interval '7 days')::bigint end);
end;
$function$;

create or replace function public._no_lock_while_unpairing()
returns trigger language plpgsql security definer set search_path to '' as $$
begin
  if exists (select 1 from public.partnerships
              where unpair_by = new.target and (owner = new.target or partner = new.target)) then
    raise exception 'unpair_pending';
  end if;
  return new;
end $$;
drop trigger if exists no_lock_while_unpairing on public.partner_locks;
create trigger no_lock_while_unpairing before insert on public.partner_locks
  for each row execute function public._no_lock_while_unpairing();

create or replace function public.lock_tamper(p_kind text, p_detail text default '')
returns bigint language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); pid uuid; mid bigint; k text := left(coalesce(p_kind, ''), 20); d text := left(regexp_replace(coalesce(p_detail, ''), '[^0-9]', '', 'g'), 6);
begin
  if me is null then raise exception 'not_signed_in'; end if;
  if k not in ('stopped', 'overlay_off', 'admin_off', 'time_changed', 'emergency_dial') then raise exception 'bad_kind'; end if;
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

revoke all on function public.leave_partnership() from public, anon;
revoke all on function public.unpair_answer(boolean) from public, anon;
revoke all on function public.unpair_cancel() from public, anon;
revoke all on function public.lock_tamper(text, text) from public, anon;
revoke all on function public._unpair(uuid) from public, anon, authenticated;
revoke all on function public._unpair_due(uuid) from public, anon, authenticated;
grant execute on function public.leave_partnership() to authenticated;
grant execute on function public.unpair_answer(boolean) to authenticated;
grant execute on function public.unpair_cancel() to authenticated;
grant execute on function public.lock_tamper(text, text) to authenticated;

revoke truncate on public.partnerships, public.partner_locks, public.profiles, public.messages from authenticated, anon;
