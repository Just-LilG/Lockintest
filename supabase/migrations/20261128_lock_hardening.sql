-- Lock hardening (0.31.0).
--
--   * lock_emergency(): the app's 5-second emergency release was removed in 0.27.1, but the server function was
--     left callable "for older builds". It is an RPC any signed-in person can call with their own token, so the
--     person being locked could end their own lock instantly, from a script or any HTTP client, no app needed.
--     It now refuses. Builds older than 0.27.1 that still show the button get an error and the lock stays on.
--     (The emergency CALL is a phone feature and never needed this function.)
--
-- Return type is bigint (the app reads the answer as a chat-note id). It is dropped first in case the live
-- signature differs from the one the app expects.

drop function if exists public.lock_emergency();
create function public.lock_emergency()
returns bigint language plpgsql security definer set search_path to '' as $$
begin
  raise exception 'emergency_release_removed';
end $$;

revoke all on function public.lock_emergency() from public, anon;
grant execute on function public.lock_emergency() to authenticated;
