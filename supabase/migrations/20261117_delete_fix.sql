-- 0.8.5: deleting a message always failed. delete_message cleared the body to '', but messages_body_check
-- requires 1 to 1000 characters, so the update was rejected and the app put the message back.
-- A deleted row now keeps a one-word placeholder (the app ignores the body when deleted_at is set).
-- Also: edit_message allowed 4000 characters while the table allows 1000.

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
  update public.messages set body = 'deleted', deleted_at = now(), media = null, media_thumb = null, media_gone = true,
         voice = null, voice_ms = null, voice_wave = null, reactions = '{}'::jsonb
   where id = p_id;
end $$;

create or replace function public.edit_message(p_id bigint, p_body text)
returns void language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); t text := btrim(coalesce(p_body, ''));
begin
  if me is null then raise exception 'not_paired'; end if;
  if t = '' or char_length(t) > 1000 then raise exception 'bad_message'; end if;
  update public.messages m set body = t, edited_at = now()
   where m.id = p_id and m.sender = me and m.kind = 'text' and m.voice is null and m.media is null
     and m.deleted_at is null and m.created_at > now() - interval '15 minutes'
     and exists (select 1 from public.partnerships p where p.id = m.partnership_id and (p.owner = me or p.partner = me));
  if not found then raise exception 'cannot_edit'; end if;
end $$;
