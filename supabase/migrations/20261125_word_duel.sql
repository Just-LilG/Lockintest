-- WORD DUEL, and the private live channel the games use. Additive; nothing existing is changed.
--
-- Word Duel: one of you SETS a secret 5-letter word (and may add a short clue), the other has 6 tries to guess it, with the
-- usual feedback per letter (g = right place, y = in the word, b = not in it). After a round the roles swap. The guesser scores
-- 7 minus the number of tries (1 to 6); if they run out of tries the setter scores 2. The score carries on until someone quits.
-- - wd_play(): open the game (the one who opens it sets the first word) or return the one that exists. Drops a
--   "game_invite|wd" line in the chat.
-- - wd_set(word, clue): the setter, while status is 'setting'. Moves to 'guessing'.
-- - wd_guess(word): the guesser, while 'guessing'. Works out the feedback, a solve or a fail.
-- - wd_next(): after a round, swap roles and start again ('setting').
-- - wd_quit(), wd_state() (marks me as "here"), wd_peek() (does not).
-- The secret word never leaves the server for the guesser until the round is over: every answer is built per person by _wd_json.
-- RLS is on with no policies, like ttt_games: everything goes through these SECURITY DEFINER functions, and only the two people
-- of a partnership can touch their game. A game lives 7 days after its last change.
-- Errors: not_signed_in, not_paired, unpair_pending, no_game, not_your_turn, bad_word, dup.
--
-- Live channel: the phones talk to each other while a game is open (the mark you are holding, the letters you are typing)
-- over Supabase Realtime, on the PRIVATE broadcast topic live:<partnership id>. These two policies are what keep it private:
-- only the two people of that partnership can join or send on that topic. Nothing in it is kept (broadcast messages are not
-- stored), and the game itself never depends on it: the server functions stay the only source of truth.

create table if not exists public.wd_games (
  partnership_id uuid primary key references public.partnerships(id) on delete cascade,
  status text not null default 'setting' check (status in ('setting', 'guessing', 'solved', 'failed')),
  setter uuid not null references auth.users(id) on delete cascade,
  guesser uuid not null references auth.users(id) on delete cascade,
  word text check (word is null or word ~ '^[a-z]{5}$'),
  clue text,
  guesses text[] not null default '{}',
  round int not null default 1,
  score jsonb not null default '{}'::jsonb,
  seen jsonb not null default '{}'::jsonb,
  invited_by uuid references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
alter table public.wd_games enable row level security;

-- ---------- The live channel ----------

create or replace function public._live_ok(p_topic text)
returns boolean language sql stable security definer set search_path to '' as $$
  select p_topic like 'live:%' and exists (
    select 1 from public.partnerships p
     where p.id::text = split_part(p_topic, ':', 2)
       and p.partner is not null
       and auth.uid() in (p.owner, p.partner)
  )
$$;
revoke all on function public._live_ok(text) from public;
grant execute on function public._live_ok(text) to authenticated;

drop policy if exists "live pair can listen" on realtime.messages;
create policy "live pair can listen" on realtime.messages for select to authenticated
  using (extension = 'broadcast' and public._live_ok((select realtime.topic())));

drop policy if exists "live pair can talk" on realtime.messages;
create policy "live pair can talk" on realtime.messages for insert to authenticated
  with check (extension = 'broadcast' and public._live_ok((select realtime.topic())));

-- The topic of my pair's live channel, or null when I am not paired.
create or replace function public.live_topic()
returns text language plpgsql stable security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_other is null then return null; end if;
  return 'live:' || v_pid::text;
end $$;
revoke all on function public.live_topic() from public;
grant execute on function public.live_topic() to authenticated;

-- ---------- Word Duel ----------

-- The feedback for a guess: five letters of g (right place), y (in the word, elsewhere) or b (not in it). A letter is only
-- counted as many times as the word has it, greens first.
create or replace function public._wd_mark(p_guess text, p_word text)
returns text language plpgsql immutable set search_path to '' as $$
declare g text[] := string_to_array(p_guess, null); w text[] := string_to_array(p_word, null);
        res text[] := array['b', 'b', 'b', 'b', 'b']; i int; j int;
begin
  for i in 1..5 loop
    if g[i] = w[i] then res[i] := 'g'; w[i] := null; end if;
  end loop;
  for i in 1..5 loop
    if res[i] <> 'g' then
      for j in 1..5 loop
        if w[j] is not null and w[j] = g[i] then res[i] := 'y'; w[j] := null; exit; end if;
      end loop;
    end if;
  end loop;
  return array_to_string(res, '');
end $$;

create or replace function public._wd_json(g public.wd_games, me uuid, p_note bigint default 0)
returns jsonb language plpgsql stable security definer set search_path to '' as $$
declare other uuid := case when g.setter = me then g.guesser else g.setter end;
        am_setter boolean := g.setter = me;
        n int := coalesce(array_length(g.guesses, 1), 0);
        marks jsonb := '[]'::jsonb;
begin
  if g.word is not null then
    select coalesce(jsonb_agg(jsonb_build_object('w', x.w, 'm', public._wd_mark(x.w, g.word)) order by x.n), '[]'::jsonb)
      into marks from unnest(g.guesses) with ordinality as x(w, n);
  end if;
  return jsonb_build_object(
    'status', g.status,
    'round', g.round,
    'i_set', am_setter,
    'my_turn', (g.status = 'setting' and am_setter) or (g.status = 'guessing' and not am_setter),
    'word', case when am_setter or g.status in ('solved', 'failed') then g.word else null end,
    'clue', g.clue,
    'guesses', marks,
    'tries', 6,
    'invited_by_me', g.invited_by = me,
    'other_here', coalesce((g.seen ->> other::text)::timestamptz, 'epoch'::timestamptz) > now() - interval '9 seconds',
    'score_me', coalesce((g.score ->> me::text)::int, 0),
    'score_them', coalesce((g.score ->> other::text)::int, 0),
    'points', case g.status when 'solved' then 7 - n when 'failed' then 2 else 0 end,
    'rev', (extract(epoch from g.updated_at) * 1000)::bigint,
    'note', p_note
  );
end $$;

-- A hidden "game_move|wd|<what>" line that sends my partner their push when they are not looking at the game (what: set, solved,
-- failed, next). Returns its id (0 when none was posted) so the app can ask the push function to send it.
create or replace function public._wd_ping(p_pid uuid, me uuid, p_other uuid, p_seen jsonb, p_what text)
returns bigint language plpgsql security definer set search_path to '' as $$
declare v_id bigint := 0;
begin
  if p_other is null then return 0; end if;
  if coalesce((p_seen ->> p_other::text)::timestamptz, 'epoch'::timestamptz) > now() - interval '9 seconds' then return 0; end if;
  insert into public.messages (partnership_id, sender, kind, body)
  values (p_pid, me, 'event', 'game_move|wd|' || p_what) returning id into v_id;
  return v_id;
end $$;

create or replace function public.wd_state()
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid; g public.wd_games;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_other is null then return jsonb_build_object('status', 'none'); end if;
  select * into g from public.wd_games where partnership_id = v_pid;
  if not found then return jsonb_build_object('status', 'none'); end if;
  if g.updated_at < now() - interval '7 days' then
    delete from public.wd_games where partnership_id = v_pid;
    return jsonb_build_object('status', 'none');
  end if;
  update public.wd_games set seen = jsonb_set(seen, array[me::text], to_jsonb(now())) where partnership_id = v_pid returning * into g;
  return public._wd_json(g, me);
end $$;

create or replace function public.wd_peek()
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid; g public.wd_games;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_other is null then return jsonb_build_object('status', 'none'); end if;
  select * into g from public.wd_games where partnership_id = v_pid;
  if not found or g.updated_at < now() - interval '7 days' then return jsonb_build_object('status', 'none'); end if;
  return public._wd_json(g, me);
end $$;

create or replace function public.wd_play()
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid; g public.wd_games; v_mid bigint := 0; v_have boolean;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_other is null then raise exception 'not_paired'; end if;
  if exists (select 1 from public.partnerships where id = v_pid and unpair_by is not null) then raise exception 'unpair_pending'; end if;
  select * into g from public.wd_games where partnership_id = v_pid;
  v_have := found;
  if v_have and g.updated_at < now() - interval '7 days' then
    delete from public.wd_games where partnership_id = v_pid;
    v_have := false;
  end if;
  if not v_have then
    insert into public.wd_games (partnership_id, setter, guesser, invited_by, seen)
    values (v_pid, me, v_other, me, jsonb_build_object(me::text, now())) returning * into g;
    insert into public.messages (partnership_id, sender, kind, body) values (v_pid, me, 'event', 'game_invite|wd') returning id into v_mid;
  else
    update public.wd_games set seen = jsonb_set(seen, array[me::text], to_jsonb(now())) where partnership_id = v_pid returning * into g;
  end if;
  return public._wd_json(g, me, v_mid);
end $$;

create or replace function public.wd_set(p_word text, p_clue text default null)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid; g public.wd_games; w text; c text; v_note bigint;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_other is null then raise exception 'not_paired'; end if;
  select * into g from public.wd_games where partnership_id = v_pid for update;
  if not found then raise exception 'no_game'; end if;
  if g.setter <> me or g.status <> 'setting' then raise exception 'not_your_turn'; end if;
  w := lower(trim(coalesce(p_word, '')));
  if w !~ '^[a-z]{5}$' then raise exception 'bad_word'; end if;
  c := nullif(left(trim(coalesce(p_clue, '')), 40), '');
  update public.wd_games set word = w, clue = c, guesses = '{}', status = 'guessing', updated_at = now()
   where partnership_id = v_pid returning * into g;
  v_note := public._wd_ping(v_pid, me, v_other, g.seen, 'set');
  return public._wd_json(g, me, v_note);
end $$;

create or replace function public.wd_guess(p_word text)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid; g public.wd_games; w text; n int; v_note bigint := 0;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_other is null then raise exception 'not_paired'; end if;
  select * into g from public.wd_games where partnership_id = v_pid for update;
  if not found then raise exception 'no_game'; end if;
  if g.guesser <> me or g.status <> 'guessing' then raise exception 'not_your_turn'; end if;
  w := lower(trim(coalesce(p_word, '')));
  if w !~ '^[a-z]{5}$' then raise exception 'bad_word'; end if;
  if w = any (g.guesses) then raise exception 'dup'; end if;
  n := coalesce(array_length(g.guesses, 1), 0) + 1;
  if w = g.word then
    update public.wd_games
       set guesses = array_append(guesses, w), status = 'solved', updated_at = now(),
           score = jsonb_set(score, array[me::text], to_jsonb(coalesce((score ->> me::text)::int, 0) + (7 - n)))
     where partnership_id = v_pid returning * into g;
    v_note := public._wd_ping(v_pid, me, v_other, g.seen, 'solved');
  elsif n >= 6 then
    update public.wd_games
       set guesses = array_append(guesses, w), status = 'failed', updated_at = now(),
           score = jsonb_set(score, array[g.setter::text], to_jsonb(coalesce((score ->> g.setter::text)::int, 0) + 2))
     where partnership_id = v_pid returning * into g;
    v_note := public._wd_ping(v_pid, me, v_other, g.seen, 'failed');
  else
    update public.wd_games set guesses = array_append(guesses, w), updated_at = now() where partnership_id = v_pid returning * into g;
  end if;
  return public._wd_json(g, me, v_note);
end $$;

create or replace function public.wd_next()
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid; g public.wd_games; v_note bigint;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_other is null then raise exception 'not_paired'; end if;
  select * into g from public.wd_games where partnership_id = v_pid for update;
  if not found or g.status not in ('solved', 'failed') then raise exception 'no_game'; end if;
  update public.wd_games
     set setter = guesser, guesser = setter, word = null, clue = null, guesses = '{}', status = 'setting',
         round = round + 1, updated_at = now()
   where partnership_id = v_pid returning * into g;
  v_note := public._wd_ping(v_pid, me, v_other, g.seen, 'next');
  return public._wd_json(g, me, v_note);
end $$;

create or replace function public.wd_quit()
returns void language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_other is null then return; end if;
  delete from public.wd_games where partnership_id = v_pid;
end $$;

-- Only signed-in people can call these; each one checks who is asking.
do $$
declare f text;
begin
  foreach f in array array['wd_state()', 'wd_peek()', 'wd_play()', 'wd_set(text,text)', 'wd_guess(text)', 'wd_next()', 'wd_quit()'] loop
    execute format('revoke all on function public.%s from public, anon', f);
    execute format('grant execute on function public.%s to authenticated', f);
  end loop;
end $$;
