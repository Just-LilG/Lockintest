-- A partner lock counts in the locked person's stats even if their phone never saw it end.
-- Before: only the app recorded it (when its lock screen saw the end). If LockIn was killed mid-lock and only
-- opened after the lock was over, the hours were lost.
-- Now: when a lock gets its ended_at, the server writes the session itself. It uses the same id the app uses
-- (Java UUID.nameUUIDFromBytes("lockin-partner-lock:<id>")), so when the app also sends it, one copy is kept.

create or replace function public._lock_session_id(p_lock bigint)
returns uuid language plpgsql immutable set search_path to '' as $$
declare b bytea := decode(md5('lockin-partner-lock:' || p_lock::text), 'hex');
begin
  b := set_byte(b, 6, (get_byte(b, 6) & 15) | 48);   -- version 3
  b := set_byte(b, 8, (get_byte(b, 8) & 63) | 128);  -- IETF variant
  return encode(b, 'hex')::uuid;
end $$;

create or replace function public._lock_to_session()
returns trigger language plpgsql security definer set search_path to '' as $$
declare
  v_start timestamptz;
  v_held int;
  v_name text;
begin
  if old.ended_at is not null or new.ended_at is null then return new; end if;
  -- Everything sits in one guarded block: stats are a nice-to-have here and must never stop a lock from ending.
  begin
    v_start := coalesce(new.starts_at, new.started_at);
    v_held := floor(extract(epoch from (least(new.ended_at, new.ends_at) - v_start)))::int;
    -- A scheduled lock cancelled before it began, or a mis-tap.
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

drop trigger if exists partner_locks_to_session on public.partner_locks;
create trigger partner_locks_to_session
  after update of ended_at on public.partner_locks
  for each row execute function public._lock_to_session();

revoke all on function public._lock_session_id(bigint) from public, anon, authenticated;
revoke all on function public._lock_to_session() from public, anon, authenticated;
