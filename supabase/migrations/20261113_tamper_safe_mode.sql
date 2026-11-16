-- The locked phone can now report that it was restarted into safe mode during a lock (LockIn can't run there,
-- so it notices afterwards from Android's start-up count). Same function, one more kind.

create or replace function public.lock_tamper(p_kind text, p_detail text default '')
returns bigint language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); pid uuid; mid bigint; k text := left(coalesce(p_kind, ''), 20); d text := left(regexp_replace(coalesce(p_detail, ''), '[^0-9]', '', 'g'), 6);
begin
  if me is null then raise exception 'not_signed_in'; end if;
  if k not in ('stopped', 'overlay_off', 'admin_off', 'time_changed', 'emergency_dial', 'safe_mode') then raise exception 'bad_kind'; end if;
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
