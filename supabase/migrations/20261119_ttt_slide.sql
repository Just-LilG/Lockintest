-- Tic-tac-toe, round 3: SLIDING MARKS, and games you can play at your own pace. Additive on 20261114 + 20261115.
--
-- The rule: each player places 3 marks. From their 4th turn on, a player does not place a new mark: they pick up one of their
-- OWN marks and put it on any empty square (ttt_slide(from, to)). Nothing ever vanishes. 3 in a row wins, checked after every
-- placement and every slide. There is no draw (a board with 6 marks always leaves room to move).
-- ttt_move(cell) now only places while you have fewer than 3 marks; with 3 it raises 'must_slide'. ttt_slide raises 'must_place'
-- while you have fewer than 3. Other errors: not_signed_in, not_paired, no_game, not_your_turn, bad_cell, unpair_pending.
-- A new game starts as 'playing' with the inviter to move (no waiting for the partner to join).
-- Games are no longer tied to both phones being at the board: a game lives 7 days, and each move while your partner is away
-- posts a hidden 'game_move|ttt[|won]' event, which is what sends their push. The app does not show those lines in the chat.
-- _ttt_json keeps every old key. New: rule 'slide', must_slide (it is my turn and I have 3 marks), last_from / last_to (the
-- squares of the latest slide, or last_to alone for a placement), and fade_me / fade_them stay null.

alter table public.ttt_games add column if not exists last_from int;
alter table public.ttt_games add column if not exists last_to int;

create or replace function public._ttt_count(p_board text, p_mark text)
returns int language sql immutable set search_path to '' as $$
  select char_length(p_board) - char_length(replace(p_board, p_mark, ''))
$$;

create or replace function public._ttt_json(g public.ttt_games, me uuid, p_note bigint default 0)
returns jsonb language plpgsql stable security definer set search_path to '' as $$
declare other uuid := case when g.x_player = me then g.o_player else g.x_player end;
        my_mark text := case when g.x_player = me then 'X' else 'O' end;
begin
  return jsonb_build_object(
    'status', g.status,
    'board', g.board,
    'i_am', my_mark,
    'my_turn', g.status = 'playing' and g.turn = me,
    'winner', case when g.winner is null then null when g.winner = me then 'me' else 'them' end,
    'line', to_jsonb(g.line),
    'invited_by_me', g.invited_by = me,
    'other_here', other is not null and coalesce((g.seen ->> other::text)::timestamptz, 'epoch'::timestamptz) > now() - interval '9 seconds',
    'wins_me', coalesce((g.score ->> me::text)::int, 0),
    'wins_them', coalesce((g.score ->> coalesce(other::text, ''))::int, 0),
    'rev', (extract(epoch from g.updated_at) * 1000)::bigint,
    'note', p_note,
    'rule', 'slide',
    'must_slide', g.status = 'playing' and g.turn = me and public._ttt_count(g.board, my_mark) >= 3,
    'last_from', to_jsonb(g.last_from),
    'last_to', to_jsonb(g.last_to),
    'fade_me', null,
    'fade_them', null
  );
end $$;

-- Tells my partner it is their turn (or that I won) when they are not at the board, and returns the id of that line (0 when
-- none was posted) so the app can send the push for it, like it does for every message. Never posted while they are looking.
drop function if exists public._ttt_ping(uuid, uuid, uuid, jsonb, boolean);
create or replace function public._ttt_ping(p_pid uuid, me uuid, p_other uuid, p_seen jsonb, p_won boolean)
returns bigint language plpgsql security definer set search_path to '' as $$
declare v_id bigint := 0;
begin
  if p_other is null then return 0; end if;
  if coalesce((p_seen ->> p_other::text)::timestamptz, 'epoch'::timestamptz) > now() - interval '9 seconds' then return 0; end if;
  insert into public.messages (partnership_id, sender, kind, body)
  values (p_pid, me, 'event', case when p_won then 'game_move|ttt|won' else 'game_move|ttt' end) returning id into v_id;
  return v_id;
end $$;

create or replace function public.ttt_state()
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid; g public.ttt_games;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_other is null then return jsonb_build_object('status', 'none'); end if;
  select * into g from public.ttt_games where partnership_id = v_pid;
  if not found then return jsonb_build_object('status', 'none'); end if;
  if g.updated_at < now() - interval '7 days' then
    delete from public.ttt_games where partnership_id = v_pid;
    return jsonb_build_object('status', 'none');
  end if;
  update public.ttt_games set seen = jsonb_set(seen, array[me::text], to_jsonb(now())) where partnership_id = v_pid
  returning * into g;
  return public._ttt_json(g, me);
end $$;

-- Join the game my partner opened, or open one. The chat invite goes out at most once per 10 minutes per sender.
create or replace function public.ttt_play()
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid; g public.ttt_games; v_mid bigint := 0; v_have boolean := false;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_other is null then raise exception 'not_paired'; end if;
  if exists (select 1 from public.partnerships where id = v_pid and unpair_by is not null) then raise exception 'unpair_pending'; end if;
  select * into g from public.ttt_games where partnership_id = v_pid;
  v_have := found;
  if v_have and g.updated_at < now() - interval '7 days' then
    delete from public.ttt_games where partnership_id = v_pid;
    v_have := false;
  end if;
  if not v_have then
    -- The game starts right away, with the first move mine: my partner can pick it up whenever they like.
    insert into public.ttt_games (partnership_id, x_player, o_player, turn, status, invited_by, seen)
    values (v_pid, me, v_other, me, 'playing', me, jsonb_build_object(me::text, now())) returning * into g;
    if not exists (select 1 from public.messages m
                    where m.partnership_id = v_pid and m.sender = me and m.kind = 'event' and m.body = 'game_invite|ttt'
                      and m.created_at > now() - interval '10 minutes') then
      insert into public.messages (partnership_id, sender, kind, body) values (v_pid, me, 'event', 'game_invite|ttt') returning id into v_mid;
    end if;
  elsif g.status = 'waiting' and g.x_player = v_other then
    update public.ttt_games
       set o_player = me, status = 'playing', turn = x_player, updated_at = now(),
           seen = jsonb_set(seen, array[me::text], to_jsonb(now()))
     where partnership_id = v_pid returning * into g;
  else
    update public.ttt_games set seen = jsonb_set(seen, array[me::text], to_jsonb(now())) where partnership_id = v_pid returning * into g;
  end if;
  return public._ttt_json(g, me, v_mid);
end $$;

-- Place a mark (0 to 8). Only while I have fewer than 3 marks; after that I slide (ttt_slide).
create or replace function public.ttt_move(p_cell int)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid; g public.ttt_games; mark text; b text; ln int[]; v_note bigint;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_other is null then raise exception 'not_paired'; end if;
  if exists (select 1 from public.partnerships where id = v_pid and unpair_by is not null) then raise exception 'unpair_pending'; end if;
  select * into g from public.ttt_games where partnership_id = v_pid for update;
  if not found or g.status <> 'playing' then raise exception 'no_game'; end if;
  if g.turn <> me then raise exception 'not_your_turn'; end if;
  if p_cell is null or p_cell < 0 or p_cell > 8 or substr(g.board, p_cell + 1, 1) <> '.' then raise exception 'bad_cell'; end if;
  mark := case when g.x_player = me then 'X' else 'O' end;
  if public._ttt_count(g.board, mark) >= 3 then raise exception 'must_slide'; end if;
  b := overlay(g.board placing mark from p_cell + 1 for 1);
  ln := public._ttt_line(b);
  if ln is not null then
    update public.ttt_games
       set board = b, moves = moves || p_cell, status = 'won', winner = me, line = ln, turn = null, updated_at = now(),
           last_from = null, last_to = p_cell,
           score = jsonb_set(score, array[me::text], to_jsonb(coalesce((score ->> me::text)::int, 0) + 1))
     where partnership_id = v_pid returning * into g;
  else
    update public.ttt_games set board = b, moves = moves || p_cell, turn = v_other, updated_at = now(), last_from = null, last_to = p_cell
     where partnership_id = v_pid returning * into g;
  end if;
  v_note := public._ttt_ping(v_pid, me, v_other, g.seen, g.status = 'won');
  return public._ttt_json(g, me, v_note);
end $$;

-- Pick up one of my marks and put it on any empty square. Only once I have 3 marks out.
create or replace function public.ttt_slide(p_from int, p_to int)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid; g public.ttt_games; mark text; b text; ln int[]; v_note bigint;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_other is null then raise exception 'not_paired'; end if;
  if exists (select 1 from public.partnerships where id = v_pid and unpair_by is not null) then raise exception 'unpair_pending'; end if;
  select * into g from public.ttt_games where partnership_id = v_pid for update;
  if not found or g.status <> 'playing' then raise exception 'no_game'; end if;
  if g.turn <> me then raise exception 'not_your_turn'; end if;
  mark := case when g.x_player = me then 'X' else 'O' end;
  if public._ttt_count(g.board, mark) < 3 then raise exception 'must_place'; end if;
  if p_from is null or p_to is null or p_from < 0 or p_from > 8 or p_to < 0 or p_to > 8 or p_from = p_to
     or substr(g.board, p_from + 1, 1) <> mark or substr(g.board, p_to + 1, 1) <> '.' then raise exception 'bad_cell'; end if;
  b := overlay(g.board placing '.' from p_from + 1 for 1);
  b := overlay(b placing mark from p_to + 1 for 1);
  ln := public._ttt_line(b);
  if ln is not null then
    update public.ttt_games
       set board = b, moves = array_append(array_remove(moves, p_from), p_to), status = 'won', winner = me, line = ln, turn = null,
           updated_at = now(), last_from = p_from, last_to = p_to,
           score = jsonb_set(score, array[me::text], to_jsonb(coalesce((score ->> me::text)::int, 0) + 1))
     where partnership_id = v_pid returning * into g;
  else
    update public.ttt_games
       set board = b, moves = array_append(array_remove(moves, p_from), p_to), turn = v_other, updated_at = now(),
           last_from = p_from, last_to = p_to
     where partnership_id = v_pid returning * into g;
  end if;
  v_note := public._ttt_ping(v_pid, me, v_other, g.seen, g.status = 'won');
  return public._ttt_json(g, me, v_note);
end $$;

-- A new board after a game. The one who went second goes first. If the other player already started the new game, this
-- just returns it.
create or replace function public.ttt_again()
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid; g public.ttt_games; v_note bigint;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_other is null then raise exception 'not_paired'; end if;
  if exists (select 1 from public.partnerships where id = v_pid and unpair_by is not null) then raise exception 'unpair_pending'; end if;
  select * into g from public.ttt_games where partnership_id = v_pid for update;
  if not found then raise exception 'no_game'; end if;
  if g.status = 'playing' and g.board = '.........' then
    update public.ttt_games set seen = jsonb_set(seen, array[me::text], to_jsonb(now())) where partnership_id = v_pid returning * into g;
    return public._ttt_json(g, me);
  end if;
  if g.status not in ('won', 'draw') then raise exception 'no_game'; end if;
  update public.ttt_games
     set board = '.........', moves = '{}', status = 'playing', winner = null, line = null, last_from = null, last_to = null,
         x_player = o_player, o_player = x_player, turn = o_player, updated_at = now(),
         seen = jsonb_set(seen, array[me::text], to_jsonb(now()))
   where partnership_id = v_pid returning * into g;
  v_note := public._ttt_ping(v_pid, me, v_other, g.seen, false);
  return public._ttt_json(g, me, v_note);
end $$;

revoke all on function public.ttt_state(), public.ttt_play(), public.ttt_move(int), public.ttt_slide(int, int), public.ttt_again(), public.ttt_quit() from public, anon;
grant execute on function public.ttt_state(), public.ttt_play(), public.ttt_move(int), public.ttt_slide(int, int), public.ttt_again(), public.ttt_quit() to authenticated;
revoke all on function public._ttt_count(text, text), public._ttt_ping(uuid, uuid, uuid, jsonb, boolean) from public, anon, authenticated;

-- A look at the game that does NOT tell my partner I have it open (ttt_state does). The chat uses this for its game card,
-- so that a partner's move still sends a push while I am only reading the chat.
create or replace function public.ttt_peek()
returns jsonb language plpgsql stable security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid; g public.ttt_games;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_other is null then return jsonb_build_object('status', 'none'); end if;
  select * into g from public.ttt_games where partnership_id = v_pid;
  if not found or g.updated_at < now() - interval '7 days' then return jsonb_build_object('status', 'none'); end if;
  return public._ttt_json(g, me);
end $$;
revoke all on function public.ttt_peek() from public, anon;
grant execute on function public.ttt_peek() to authenticated;
