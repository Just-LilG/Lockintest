-- The locked person withdraws a pending unlock request (before the partner has answered).
create or replace function public.lock_ask_cancel()
 returns boolean language plpgsql security definer set search_path to '' as $function$
declare
  me uuid := auth.uid();
  n int;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  update public.partner_locks
     set unlock_asked_at = null, unlock_reason = null
   where target = me and ended_at is null and starts_at <= now()
     and unlock_asked_at is not null and unlock_answer is null;
  get diagnostics n = row_count;
  return n > 0;
end;
$function$;
revoke all on function public.lock_ask_cancel() from public, anon;
grant execute on function public.lock_ask_cancel() to authenticated;
