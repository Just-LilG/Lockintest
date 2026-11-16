-- Notification kinds you switched off (You -> Notifications), kept on the server too.
-- Pushes now arrive as real notifications that Android shows by itself, even while LockIn is asleep, so the
-- server has to know which kinds you don't want: those are sent silently (data only) and LockIn skips them.
alter table public.profiles add column if not exists notify_off text[] not null default '{}';

create or replace function public.set_notify_off(p_kinds text[])
returns json language plpgsql security definer set search_path = public as $$
declare v text[];
begin
  if auth.uid() is null then raise exception 'not_signed_in'; end if;
  select coalesce(array_agg(distinct k), '{}') into v
  from unnest(coalesce(p_kinds, '{}')) k
  where k in ('messages', 'locks', 'plans', 'nudges', 'limits');
  insert into public.profiles (id, notify_off) values (auth.uid(), v)
  on conflict (id) do update set notify_off = excluded.notify_off;
  return json_build_object('off', v);
end $$;

revoke all on function public.set_notify_off(text[]) from public, anon;
grant execute on function public.set_notify_off(text[]) to authenticated;
