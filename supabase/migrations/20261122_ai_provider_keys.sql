-- 0.19.1: keys for the AI function's providers (Groq), kept out of the app.
-- Applied to the live project as migration "ai_provider_keys". The key itself is NOT in this file: it was inserted
-- separately with  insert into private.ai_keys (name, value) values ('groq', '<key>')
--   on conflict (name) do update set value = excluded.value, updated_at = now();
create schema if not exists private;
revoke all on schema private from public, anon, authenticated;
create table if not exists private.ai_keys (name text primary key, value text not null, updated_at timestamptz not null default now());
revoke all on private.ai_keys from public, anon, authenticated;

create or replace function public.ai_key(p_name text) returns text
language sql security definer set search_path = private, public stable as $$
  select value from private.ai_keys where name = p_name
$$;
revoke all on function public.ai_key(text) from public, anon, authenticated;
grant execute on function public.ai_key(text) to service_role;
