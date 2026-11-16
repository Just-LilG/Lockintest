-- Message sounds: you pick the sound your partner's phone plays when YOU message them.
-- The push function reads the sender's choice and sends it with each message.
alter table public.profiles add column if not exists message_sound text not null default 'message';

create or replace function public.set_message_sound(p_sound text)
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'not_signed_in'; end if;
  if p_sound not in ('message', 'fah', 'oh_no', 'pikachu', 'rain_hush', 'sneeze', 'whistle') then
    raise exception 'bad_sound';
  end if;
  insert into public.profiles (id, message_sound) values (auth.uid(), p_sound)
  on conflict (id) do update set message_sound = excluded.message_sound;
  return json_build_object('sound', p_sound);
end $$;

create or replace function public.profile_me()
returns json language sql stable security definer set search_path = public as $$
  select json_build_object(
    'name', coalesce(p.display_name, ''),
    'email', coalesce(u.email, ''),
    'created_at', extract(epoch from coalesce(p.created_at, u.created_at))::bigint,
    'sound', coalesce(p.message_sound, 'message')
  )
  from auth.users u left join public.profiles p on p.id = u.id
  where u.id = auth.uid();
$$;

revoke all on function public.set_message_sound(text) from public, anon;
grant execute on function public.set_message_sound(text) to authenticated;
