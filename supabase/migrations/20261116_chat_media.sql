-- Photos, edits, deletes and reactions in the partner chat, and a relay that keeps the server small.
--
-- THE RELAY (why the server stays small): a photo or voice note is only passing through.
--   1. The sender uploads it (private bucket "chat-media" for photos, "voice" for recordings) and keeps their own copy.
--   2. The recipient's phone downloads it once and keeps it in the app's own storage, then calls ack_media().
--   3. Six hours after that (a grace period, in case the same person is signed in on a second device) the file is
--      deleted from the bucket. A file nobody ever opened is deleted after 7 days. Crew voice notes after 30 days.
--   The chat row stays (with a tiny blurred preview for photos), so the history reads the same; a photo that is
--   gone from the server and from this phone shows as "No longer available".
-- Deleting files needs Storage's own API (deleting rows from storage.objects would leave the bytes behind), so the
-- hourly job below calls the "media-sweep" Edge Function, which asks media_due() what is due and removes it.
--
-- messages gets:
--   media / media_w / media_h / media_thumb / media_bytes   the photo (path in "chat-media", size, a ~24px base64 preview)
--   media_gone    the server's copy has been deleted
--   acked_at      the recipient has it on their phone
--   edited_at / deleted_at / reactions {userId: emoji} / updated_at   so edits, deletes and reactions reach the other phone:
--                 the app polls "updated_at >= last seen", which returns new rows too.
-- A photo message is a 'text' row whose body is its caption, or "Photo", so an older LockIn still shows something.

alter table public.messages add column if not exists media text;
alter table public.messages add column if not exists media_w int;
alter table public.messages add column if not exists media_h int;
alter table public.messages add column if not exists media_thumb text;
alter table public.messages add column if not exists media_bytes int;
alter table public.messages add column if not exists media_gone boolean not null default false;
alter table public.messages add column if not exists acked_at timestamptz;
alter table public.messages add column if not exists edited_at timestamptz;
alter table public.messages add column if not exists deleted_at timestamptz;
alter table public.messages add column if not exists reactions jsonb not null default '{}'::jsonb;
alter table public.messages add column if not exists updated_at timestamptz not null default now();
alter table public.crew_messages add column if not exists voice_gone boolean not null default false;

do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'messages_media_ok') then
    alter table public.messages add constraint messages_media_ok check (
      media is null or (
        media like 'p-' || partnership_id::text || '/%' and char_length(media) < 200
        and media_w between 1 and 8000 and media_h between 1 and 8000
        and (media_thumb is null or char_length(media_thumb) < 6000)
        and (media_bytes is null or media_bytes between 0 and 6000000)
      )
    );
  end if;
end $$;

create index if not exists messages_sync_idx on public.messages (partnership_id, updated_at);

-- Whatever a sender puts in the bookkeeping columns is ignored on insert; they change only through the functions below.
create or replace function public._messages_fresh()
returns trigger language plpgsql security definer set search_path to '' as $$
begin
  new.media_gone := false;
  new.acked_at := null;
  new.edited_at := null;
  new.deleted_at := null;
  new.reactions := '{}'::jsonb;
  new.updated_at := now();
  return new;
end $$;
drop trigger if exists messages_fresh on public.messages;
create trigger messages_fresh before insert on public.messages
  for each row execute function public._messages_fresh();

create or replace function public._messages_touch()
returns trigger language plpgsql security definer set search_path to '' as $$
begin
  new.updated_at := now();
  return new;
end $$;
drop trigger if exists messages_touch on public.messages;
create trigger messages_touch before update on public.messages
  for each row execute function public._messages_touch();

-- Files waiting to be deleted from storage (a message you deleted took its file with it).
create table if not exists public.media_trash (
  bucket text not null,
  name text not null,
  primary key (bucket, name)
);
alter table public.media_trash enable row level security; -- no policies: only the service role touches it

-- ---- Photos bucket --------------------------------------------------------------------------

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('chat-media', 'chat-media', false, 3145728, array['image/jpeg'])
on conflict (id) do update set public = false, file_size_limit = excluded.file_size_limit, allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "chat-media: members read" on storage.objects;
create policy "chat-media: members read" on storage.objects for select to authenticated
  using (bucket_id = 'chat-media' and public._voice_member(name));

drop policy if exists "chat-media: members upload" on storage.objects;
create policy "chat-media: members upload" on storage.objects for insert to authenticated
  with check (bucket_id = 'chat-media' and public._voice_member(name));

-- ---- Edit, delete, react, acknowledge ---------------------------------------------------------

-- Fix a typo in your own text message, within 15 minutes of sending it.
create or replace function public.edit_message(p_id bigint, p_body text)
returns void language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); t text := btrim(coalesce(p_body, ''));
begin
  if me is null then raise exception 'not_paired'; end if;
  if t = '' or char_length(t) > 4000 then raise exception 'bad_message'; end if;
  update public.messages m set body = t, edited_at = now()
   where m.id = p_id and m.sender = me and m.kind = 'text' and m.voice is null and m.media is null
     and m.deleted_at is null and m.created_at > now() - interval '15 minutes'
     and exists (select 1 from public.partnerships p where p.id = m.partnership_id and (p.owner = me or p.partner = me));
  if not found then raise exception 'cannot_edit'; end if;
end $$;

-- Delete your own message for both of you. Its voice note or photo is queued for removal from storage.
create or replace function public.delete_message(p_id bigint)
returns void language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); m public.messages;
begin
  if me is null then raise exception 'not_paired'; end if;
  select * into m from public.messages x
   where x.id = p_id and x.sender = me and x.kind = 'text' and x.deleted_at is null
     and exists (select 1 from public.partnerships p where p.id = x.partnership_id and (p.owner = me or p.partner = me));
  if not found then raise exception 'cannot_edit'; end if;
  if m.media is not null and not m.media_gone then
    insert into public.media_trash (bucket, name) values ('chat-media', m.media) on conflict do nothing;
  end if;
  if m.voice is not null and not m.media_gone then
    insert into public.media_trash (bucket, name) values ('voice', m.voice) on conflict do nothing;
  end if;
  update public.messages set body = '', deleted_at = now(), media = null, media_thumb = null, media_gone = true,
         voice = null, voice_ms = null, voice_wave = null, reactions = '{}'::jsonb
   where id = p_id;
end $$;

-- Your reaction to a message (one per person). An empty emoji takes it back.
create or replace function public.react_message(p_id bigint, p_emoji text)
returns void language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); e text := left(btrim(coalesce(p_emoji, '')), 16);
begin
  if me is null then raise exception 'not_paired'; end if;
  update public.messages m
     set reactions = case when e = '' then m.reactions - me::text else jsonb_set(m.reactions, array[me::text], to_jsonb(e)) end
   where m.id = p_id and m.deleted_at is null and m.kind = 'text'
     and exists (select 1 from public.partnerships p where p.id = m.partnership_id and p.partner is not null and (p.owner = me or p.partner = me));
  if not found then raise exception 'cannot_edit'; end if;
end $$;

-- "I have it on my phone now." Starts the clock for deleting the server's copy. Only the recipient can say it.
create or replace function public.ack_media(p_id bigint)
returns void language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid();
begin
  if me is null then return; end if;
  update public.messages m set acked_at = now()
   where m.id = p_id and m.sender <> me and m.acked_at is null and (m.media is not null or m.voice is not null)
     and exists (select 1 from public.partnerships p where p.id = m.partnership_id and (p.owner = me or p.partner = me));
end $$;

revoke all on function public.edit_message(bigint, text), public.delete_message(bigint),
  public.react_message(bigint, text), public.ack_media(bigint) from public, anon;
grant execute on function public.edit_message(bigint, text), public.delete_message(bigint),
  public.react_message(bigint, text), public.ack_media(bigint) to authenticated;

-- ---- What the sweep should delete -------------------------------------------------------------

create or replace function public.media_due(p_limit int default 100)
returns table (kind text, ref bigint, bucket text, name text)
language sql stable security definer set search_path to '' as $$
  (select 'msg'::text, m.id, 'chat-media'::text, m.media
     from public.messages m
    where m.media is not null and not m.media_gone
      and ((m.acked_at is not null and m.acked_at < now() - interval '6 hours') or m.created_at < now() - interval '7 days')
    limit p_limit)
  union all
  (select 'msg'::text, m.id, 'voice'::text, m.voice
     from public.messages m
    where m.voice is not null and not m.media_gone
      and ((m.acked_at is not null and m.acked_at < now() - interval '6 hours') or m.created_at < now() - interval '7 days')
    limit p_limit)
  union all
  (select 'crew'::text, c.id, 'voice'::text, c.voice
     from public.crew_messages c
    where c.voice is not null and not c.voice_gone and c.created_at < now() - interval '30 days'
    limit p_limit)
  union all
  (select 'trash'::text, 0::bigint, t.bucket, t.name from public.media_trash t limit p_limit);
$$;

-- Called by the sweep once the files are really gone.
create or replace function public.media_done(p_msg bigint[], p_crew bigint[], p_trash text[])
returns void language plpgsql security definer set search_path to '' as $$
begin
  update public.messages set media_gone = true where id = any(coalesce(p_msg, '{}'));
  update public.crew_messages set voice_gone = true where id = any(coalesce(p_crew, '{}'));
  delete from public.media_trash t where t.bucket || '/' || t.name = any(coalesce(p_trash, '{}'));
end $$;

revoke all on function public.media_due(int), public.media_done(bigint[], bigint[], text[]) from public, anon, authenticated;
grant execute on function public.media_due(int), public.media_done(bigint[], bigint[], text[]) to service_role;
