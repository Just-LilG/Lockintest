-- Replies and voice messages, in the partner chat and the crew chat.
--
-- messages / crew_messages get:
--   reply_to    the message this one answers (shown as a small quote above it)
--   voice       path of a voice recording in the private "voice" bucket, or null
--   voice_ms    its length
--   voice_wave  32 digits 0-9, the loudness along it, for the waveform
-- A voice message is a 'text' row whose body says "Voice message", so an older LockIn still shows something.
--
-- Recordings live in the private "voice" bucket: p-<partnership id>/... for the partner chat,
-- c-<crew id>/... for a crew. Only people in that partnership or crew can upload or play them.
-- (crew_messages' columns are created in 20261113_crews.sql; this adds the partner side and the bucket.)

alter table public.messages add column if not exists reply_to bigint references public.messages(id) on delete set null;
alter table public.messages add column if not exists voice text;
alter table public.messages add column if not exists voice_ms int;
alter table public.messages add column if not exists voice_wave text;

do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'messages_voice_ok') then
    alter table public.messages add constraint messages_voice_ok check (
      (voice is null or (voice like 'p-' || partnership_id::text || '/%' and char_length(voice) < 200))
      and (voice_ms is null or voice_ms between 0 and 300000)
      and (voice_wave is null or voice_wave ~ '^[0-9]{0,64}$')
    );
  end if;
end $$;

-- A reply must answer a message in the same chat.
create or replace function public._reply_same_chat()
returns trigger language plpgsql security definer set search_path to '' as $$
begin
  if new.reply_to is not null and not exists (
    select 1 from public.messages where id = new.reply_to and partnership_id = new.partnership_id
  ) then
    new.reply_to := null;
  end if;
  return new;
end $$;
drop trigger if exists messages_reply_same_chat on public.messages;
create trigger messages_reply_same_chat before insert on public.messages
  for each row execute function public._reply_same_chat();

-- ---- The voice bucket ------------------------------------------------------------------------

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('voice', 'voice', false, 5242880, array['audio/mp4', 'audio/m4a', 'audio/x-m4a', 'audio/aac'])
on conflict (id) do update set public = false, file_size_limit = excluded.file_size_limit, allowed_mime_types = excluded.allowed_mime_types;

-- May the signed-in person use the recording at [p_name]? Its first folder names the chat.
create or replace function public._voice_member(p_name text)
returns boolean language plpgsql stable security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_folder text := split_part(coalesce(p_name, ''), '/', 1); v_id uuid;
begin
  if me is null then return false; end if;
  begin
    v_id := substr(v_folder, 3)::uuid;
  exception when others then
    return false;
  end;
  if v_folder like 'p-%' then
    return exists (select 1 from public.partnerships where id = v_id and (owner = me or partner = me) and partner is not null);
  elsif v_folder like 'c-%' then
    return exists (select 1 from public.crew_members where crew_id = v_id and user_id = me);
  end if;
  return false;
end $$;
revoke all on function public._voice_member(text) from public, anon;
grant execute on function public._voice_member(text) to authenticated;

drop policy if exists "voice: members read" on storage.objects;
create policy "voice: members read" on storage.objects for select to authenticated
  using (bucket_id = 'voice' and public._voice_member(name));

drop policy if exists "voice: members upload" on storage.objects;
create policy "voice: members upload" on storage.objects for insert to authenticated
  with check (bucket_id = 'voice' and public._voice_member(name));
