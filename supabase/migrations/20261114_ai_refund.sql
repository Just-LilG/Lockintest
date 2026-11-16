-- Gives a call back when the AI could not answer (Google busy, bad reply), so a failure never costs the day's allowance.
create or replace function public.ai_refund(p_user uuid)
returns void language sql security definer set search_path to '' as $$
  update public.ai_usage set calls = greatest(calls - 1, 0)
   where user_id = p_user and day = (now() at time zone 'utc')::date;
$$;
revoke all on function public.ai_refund(uuid) from public, anon, authenticated;
grant execute on function public.ai_refund(uuid) to service_role;
