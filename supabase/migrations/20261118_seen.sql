-- Seen ticks. The recipient's phone calls mark_seen when the message is on screen; the row's seen_at is set, the touch
-- trigger moves updated_at, and the sender's normal sync (updated_at >= since) picks it up. No extra polling.

alter table public.messages add column if not exists seen_at timestamptz;

create or replace function public._messages_fresh()
returns trigger language plpgsql security definer set search_path to '' as $$
begin
  new.media_gone := false;
  new.acked_at := null;
  new.edited_at := null;
  new.deleted_at := null;
  new.seen_at := null;
  new.reactions := '{}'::jsonb;
  new.updated_at := now();
  return new;
end $$;

-- "I have read everything up to p_upto." Marks only what my partner sent, once.
create or replace function public.mark_seen(p_upto bigint)
returns void language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid();
begin
  if me is null then return; end if;
  update public.messages m set seen_at = now()
   where m.id <= p_upto and m.sender <> me and m.seen_at is null and m.kind = 'text' and m.deleted_at is null
     and exists (select 1 from public.partnerships p where p.id = m.partnership_id and p.partner is not null and (p.owner = me or p.partner = me));
end $$;
revoke all on function public.mark_seen(bigint) from public, anon;
grant execute on function public.mark_seen(bigint) to authenticated;
