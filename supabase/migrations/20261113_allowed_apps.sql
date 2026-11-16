-- Allowed apps: up to 3 apps your partner lets you keep using while they have your phone locked
-- (WhatsApp for family, a dictionary, a music app...). The partner who locks picks them, from the apps your
-- phone shares (App usage); they hold for every lock, until changed. Nothing here touches partner_locks.
--
-- lock_allowed_apps(target, package, label, set_by): set_by is the partner who allowed it. Rows count only
-- while set_by is still target's partner, so a new partner starts with none (and they're deleted on unpair).

create table if not exists public.lock_allowed_apps (
  target uuid not null references auth.users(id) on delete cascade,
  package text not null check (char_length(package) between 1 and 200),
  label text not null check (char_length(label) between 1 and 80),
  set_by uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (target, package)
);
alter table public.lock_allowed_apps enable row level security;

-- Your partner right now, or null.
create or replace function public._partner_of(p_user uuid)
returns uuid language sql stable security definer set search_path to '' as $$
  select case when owner = p_user then partner else owner end
    from public.partnerships
   where (owner = p_user or partner = p_user) and partner is not null
   limit 1;
$$;

create or replace function public._partnership_id_of(p_user uuid)
returns uuid language sql stable security definer set search_path to '' as $$
  select id from public.partnerships where (owner = p_user or partner = p_user) and partner is not null limit 1;
$$;

-- Allow [p_package] on your partner's phone during locks. Up to 3. Returns the chat note's id (for the push).
create or replace function public.allow_app(p_package text, p_label text)
returns bigint language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_them uuid; v_pid uuid; v_label text := left(btrim(coalesce(p_label, '')), 80); v_msg bigint;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_them := public._partner_of(me);
  if v_them is null then raise exception 'not_paired'; end if;
  if coalesce(btrim(p_package), '') = '' then raise exception 'bad_app'; end if;
  if v_label = '' then v_label := p_package; end if;
  -- Rows from an old partner don't count, and are cleared now.
  delete from public.lock_allowed_apps where target = v_them and set_by <> me;
  if not exists (select 1 from public.lock_allowed_apps where target = v_them and package = p_package)
     and (select count(*) from public.lock_allowed_apps where target = v_them) >= 3 then
    raise exception 'too_many_allowed';
  end if;
  insert into public.lock_allowed_apps (target, package, label, set_by) values (v_them, p_package, v_label, me)
  on conflict (target, package) do update set label = excluded.label, set_by = excluded.set_by;
  v_pid := public._partnership_id_of(me);
  insert into public.messages (partnership_id, sender, kind, body) values (v_pid, me, 'event', 'allow_set|' || v_label)
  returning id into v_msg;
  return v_msg;
end $$;

create or replace function public.disallow_app(p_package text)
returns bigint language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_them uuid; v_label text; v_msg bigint := null;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_them := public._partner_of(me);
  if v_them is null then raise exception 'not_paired'; end if;
  delete from public.lock_allowed_apps where target = v_them and package = p_package returning label into v_label;
  if v_label is not null then
    insert into public.messages (partnership_id, sender, kind, body)
    values (public._partnership_id_of(me), me, 'event', 'allow_removed|' || v_label) returning id into v_msg;
  end if;
  return v_msg;
end $$;

-- mine: apps you may use during your partner's locks. theirs: apps you allowed on your partner's phone.
create or replace function public.allowed_apps()
returns jsonb language plpgsql stable security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_them uuid;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  v_them := public._partner_of(me);
  if v_them is null then return jsonb_build_object('mine', '[]'::jsonb, 'theirs', '[]'::jsonb); end if;
  return jsonb_build_object(
    'mine', coalesce((select jsonb_agg(jsonb_build_object('package', package, 'label', label) order by created_at)
                        from public.lock_allowed_apps where target = me and set_by = v_them), '[]'::jsonb),
    'theirs', coalesce((select jsonb_agg(jsonb_build_object('package', package, 'label', label) order by created_at)
                          from public.lock_allowed_apps where target = v_them and set_by = me), '[]'::jsonb)
  );
end $$;

-- Unpairing clears what either of you allowed.
create or replace function public._clear_allowed_on_unpair()
returns trigger language plpgsql security definer set search_path to '' as $$
begin
  if old.partner is not null then
    delete from public.lock_allowed_apps
     where (target = old.owner and set_by = old.partner) or (target = old.partner and set_by = old.owner);
  end if;
  return old;
end $$;
drop trigger if exists partnerships_clear_allowed on public.partnerships;
create trigger partnerships_clear_allowed after delete on public.partnerships
  for each row execute function public._clear_allowed_on_unpair();

revoke all on function public._partner_of(uuid) from public, anon, authenticated;
revoke all on function public._partnership_id_of(uuid) from public, anon, authenticated;
revoke all on function public._clear_allowed_on_unpair() from public, anon, authenticated;
revoke all on function public.allow_app(text, text) from public, anon;
revoke all on function public.disallow_app(text) from public, anon;
revoke all on function public.allowed_apps() from public, anon;
grant execute on function public.allow_app(text, text) to authenticated;
grant execute on function public.disallow_app(text) to authenticated;
grant execute on function public.allowed_apps() to authenticated;
