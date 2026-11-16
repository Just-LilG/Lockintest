-- The AI study helper (Edge Function "ai", Gemini). A daily allowance per person, so one account can't run up
-- the bill: ai_take() counts a call and says whether it's allowed. Only the function (service role) calls it.
create table if not exists public.ai_usage (
  user_id uuid not null references auth.users(id) on delete cascade,
  day date not null,
  calls int not null default 0,
  primary key (user_id, day)
);
alter table public.ai_usage enable row level security;

create or replace function public.ai_take(p_user uuid, p_limit int)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare v_calls int;
begin
  insert into public.ai_usage (user_id, day, calls) values (p_user, (now() at time zone 'utc')::date, 1)
  on conflict (user_id, day) do update set calls = public.ai_usage.calls + 1
  returning calls into v_calls;
  if v_calls > p_limit then
    update public.ai_usage set calls = calls - 1 where user_id = p_user and day = (now() at time zone 'utc')::date;
    return jsonb_build_object('ok', false, 'used', p_limit, 'limit', p_limit);
  end if;
  return jsonb_build_object('ok', true, 'used', v_calls, 'limit', p_limit);
end $$;
revoke all on function public.ai_take(uuid, int) from public, anon, authenticated;
grant execute on function public.ai_take(uuid, int) to service_role;
