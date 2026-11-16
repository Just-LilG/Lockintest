-- Already applied to the LockIn project (migrations "partnerships_and_messages" and "fix_create_invite_found").
-- Kept here as a record; run it only on a fresh project.
-- partnerships: one row per pair (owner made the invite, partner joined with the code).
-- messages: the chat. kind 'event' rows hold a short code (nudge, asked_unlock, emergency_release, joined).
-- All writes to partnerships go through the four functions below; the tables are read-only to the app.
-- Row-level security: only the two people in a partnership can read or write its messages, and you can
-- only send as yourself.
-- Functions (callable by signed-in users only): my_partnership(), create_invite(), join_invite(code), leave_partnership(), invite_owner(code),
-- typing_tick(), session_totals(). invite_owner(code) returns the inviter's first name so the app can ask "Pair with X?" before joining.
-- Codes are 8 characters (no 0/O/1/I), valid for 24 hours, one partner per person.
-- To see the full SQL: Supabase dashboard > Database > Migrations.
--
-- Push (migration "device_tokens", plus the Edge Function in supabase/functions/push):
-- device_tokens(token pk, user_id, updated_at): one row per phone. RLS on with no policies; only the two functions
-- register_device(p_token) and unregister_device(p_token) (signed-in users only) and the push function (service role) touch it.
--
-- App times and limits (migration "usage_sharing_and_limits"):
-- usage_settings(user_id pk, sharing default true, access default false, reported_at): what each person shares.
-- usage_days(user_id, day, package, label, seconds, limitable, updated_at): one row per app per day, sent by that person's phone.
-- app_limits(target, package, label, seconds 300..86400, set_by): a daily limit one partner set on the other's app.
-- RLS is on for all three with no policies; the app only calls these functions (signed-in users only):
-- usage_report(p_rows), usage_set(p_sharing, p_access), usage_mine(), partner_usage(p_days),
-- my_limits(), set_limit(p_package, p_seconds), remove_limit(p_package).
-- You only ever see your own partner's rows, and only while they share. set_limit/remove_limit work only on your partner and need
-- them to be sharing. Limits are deleted when the partnership ends (trigger partnerships_clear_limits).
-- Chat notes: sharing_on, sharing_off, limit_set|App|minutes, limit_removed|App.
--
-- Updates (migration "updates_bucket"): public storage bucket "updates" (200 MB limit) holding latest.json and the APKs.
-- There are no policies on storage.objects, so only the service key can upload. See scripts/publish-update.sh.
--
-- Code-only updates: latest.json in the "updates" bucket also carries a "dynamic" object:
-- {"dynamic": {"debug": {dynamicVersion, stableApiVersion, url, sha256, size, notes}, "release": {...}}}.
-- Bundles are lockin-dyn-<variant>-<version>.zip in the same bucket. See scripts/publish-dynamic.sh.
--
-- Locking your partner's phone (migrations "partner_locks", "partner_locks_fix_my_lock"):
-- partner_locks(id, locker, target, seconds 300..28800, started_at, ends_at, ended_at, ended_by time|locker|emergency|unpaired).
-- One running lock per target (partial unique index). The server holds the clock: ends_at is fixed when the lock is made.
-- RLS on, no policies. Functions (signed-in users only): lock_partner(p_seconds), unlock_partner(), lock_emergency(),
-- my_lock() (a lock on me, or how the last one ended), their_lock() (the lock I set). Locks ending by time are closed lazily.
-- Chat notes: lock_set|minutes, lock_lifted, emergency_release (now written by lock_emergency()). Deleting the partnership ends locks.

-- ---------------------------------------------------------------------------------------------
-- Daily goals and planned sessions (migration "goals_and_plans")
-- ---------------------------------------------------------------------------------------------
alter table public.profiles add column if not exists goal_minutes int not null default 120 check (goal_minutes between 15 and 960);

create table if not exists public.planned_sessions (
  id uuid primary key default gen_random_uuid(),
  partnership_id uuid not null references public.partnerships(id) on delete cascade,
  creator uuid not null references auth.users(id) on delete cascade,
  title text not null check (char_length(title) between 1 and 60),
  starts_at timestamptz not null,
  minutes int not null check (minutes between 5 and 480),
  created_at timestamptz not null default now()
);
create index if not exists planned_sessions_pair_start on public.planned_sessions (partnership_id, starts_at);
alter table public.planned_sessions enable row level security;
-- No policies on purpose: the app reaches this table only through set_goal, goals, plan_session,
-- cancel_session and my_plans, which are SECURITY DEFINER and executable by signed-in users only.
-- (Function bodies: see the migration in the Supabase project; they check the pair with _pair_of(),
-- validate title 1-60 chars, length 5-480 min, start within the next 60 days, at most 30 plans, and
-- write a chat event plan_set|title|epoch or plan_canceled|title|epoch.)


-- Lock v2 (migrations "partner_locks_v2", "partner_locks_v2_fix_strict"):
-- partner_locks gained message, strict (no unlock requests), together, pickups, extended_seconds,
-- unlock_asked_at / unlock_reason / unlock_answer / unlock_answered_at. profiles gained lock_allowed and
-- lock_max_seconds (900..28800). lock_partner(p_seconds, p_message, p_strict, p_together) replaced the one-argument version.
-- New RPCs: lock_ask(reason), lock_answer(approve), lock_report(pickups), lock_extend(seconds 300..3600),
-- lock_history(limit), lock_rules(), lock_rules_set(allowed, max). their_lock() and my_lock() return the extra fields.
-- New chat events: lock_extended|minutes, unlock_denied. All RPCs are executable by authenticated only.
-- lock_ask_cancel() (migration 20261126): the locked person withdraws an unanswered unlock request.

-- Scheduled locks (migration "partner_locks_v3_scheduled"):
-- partner_locks.starts_at (not null, default now()). lock_partner(p_seconds, p_message, p_strict, p_together, p_start_in seconds 0..604800).
-- their_lock() adds starts_in; my_lock() answers state 'scheduled' (with starts_in) until it begins, then 'active'.
-- lock_emergency, lock_ask and lock_report only act on locks that have begun. unlock_partner on a lock that has not begun
-- cancels it and writes 'lock_canceled'. lock_history adds 'canceled' and measures 'held' from starts_at.
-- New chat events: lock_planned|minutes|epochSeconds, lock_canceled.

-- You tab (migration "profile_me_and_name"):
--   profile_me() -> {name, email, created_at (epoch seconds)} for the signed-in person.
--   set_display_name(p_name) trims, checks 1..40 chars, upserts profiles.display_name and mirrors it into
--   auth user metadata (name, display_name). Both SECURITY DEFINER, execute granted to authenticated only.

-- Lock hardening round 2 (migration "lock_heartbeat"): partner_locks.last_beat, partner_locks.escapes;
--   lock_beat(p_pickups, p_escapes) -> {state, id, remaining} for the locked phone, every 15 s;
--   their_lock() also returns beat_ago (seconds since last_beat, -1 if never) and escapes.
