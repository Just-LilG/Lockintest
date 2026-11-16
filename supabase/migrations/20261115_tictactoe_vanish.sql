-- Tic-tac-toe, round 2: VANISHING MARKS. Additive on top of 20261114_tictactoe.sql (functions are replaced, no data dropped).
--
-- The rule: every player can have at most 3 marks on the board. When a player who already has 3 marks plays, their
-- OLDEST mark is removed in that same move, the new mark is placed, and only then is 3-in-a-row checked. The target cell
-- must be empty BEFORE the removal, so you can never play onto your own about-to-vanish mark. Because there are never more
-- than 6 marks on 9 cells a draw can no longer happen; 'draw' stays in the status check for old rows and old clients.
-- ttt_games.moves = the cells of the marks currently on the board in placement order, oldest first, both players mixed.
-- Games that were already running (a board but empty moves) are handled: marks missing from moves are treated as older
-- than the known ones and ordered by cell number for their player. (A legacy player with more than 3 marks loses the extra
-- oldest ones on their next move so they end with 3.)
-- _ttt_json keeps every old key and adds: rule ('vanish'), fade_me / fade_them = the cell (0-8) of my / their mark that will
-- vanish on that player's next move (only while playing and that player has 3 marks, else null).
-- Hardening: ttt_again is idempotent when both players tap "Play again" (the second call just returns the new game);
-- the chat invite is posted at most once per 10 minutes per sender; ttt_move and ttt_again raise 'unpair_pending' like
-- ttt_play does. ttt_quit always works. Error codes: not_signed_in, not_paired, no_game, not_your_turn, bad_cell, unpair_pending.

alter table public.ttt_games add column if not exists moves int[] not null default '{}';

-- Marks on the board in placement order (oldest first): known order from p_moves first (only cells still marked),
-- then any marked cell p_moves does not know about, by cell number.
create or replace function public._ttt_order(p_board text, p_moves int[])
returns int[] language plpgsql immutable set search_path to '' as $$
declare r int[] := '{}'; c int;
begin
  if p_moves is not null then
    foreach c in array p_moves loop
      if c between 0 and 8 and substr(p_board, c + 1, 1) <> '.' and not (c = any (r)) then r := r || c; end if;
    end loop;
  end if;
  for c in 0..8 loop
    if substr(p_board, c + 1, 1) <> '.' and not (c = any (r)) then r := r || c; end if;
  end loop;
  return r;
end $$;

-- The cells of one player's marks ('X' or 'O'), oldest first.
create or replace function public._ttt_mine(p_board text, p_moves int[], p_mark text)
returns int[] language plpgsql immutable set search_path to '' as $$
declare r int[] := '{}'; c int;
begin
  foreach c in array public._ttt_order(p_board, p_moves) loop
    if substr(p_board, c + 1, 1) = p_mark then r := r || c; end if;
  end loop;
  return r;
end $$;

-- The mark of that player that vanishes on their next move, or null while they have fewer than 3.
create or replace function public._ttt_fade(p_board text, p_moves int[], p_mark text)
returns int language plpgsql immutable set search_path to '' as $$
declare m int[] := public._ttt_mine(p_board, p_moves, p_mark);
begin
  if cardinality(m) >= 3 then return m[1]; end if;
  return null;
end $$;

create or replace function public._ttt_json(g public.ttt_games, me uuid, p_note bigint default 0)
returns jsonb language plpgsql stable security definer set search_path to '' as $$
declare other uuid := case when g.x_player = me then g.o_player else g.x_player end;
        my_mark text := case when g.x_player = me then 'X' else 'O' end;
        their_mark text := case when g.x_player = me then 'O' else 'X' end;
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
    'rule', 'vanish',
    'fade_me', case when g.status = 'playing' then to_jsonb(public._ttt_fade(g.board, g.moves, my_mark)) else null end,
    'fade_them', case when g.status = 'playing' then to_jsonb(public._ttt_fade(g.board, g.moves, their_mark)) else null end
  );
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
  if v_have and (g.updated_at < now() - interval '30 minutes' or (g.status = 'waiting' and g.updated_at < now() - interval '10 minutes')) then
    delete from public.ttt_games where partnership_id = v_pid;
    v_have := false;
  end if;
  if not v_have then
    insert into public.ttt_games (partnership_id, x_player, invited_by, seen)
    values (v_pid, me, me, jsonb_build_object(me::text, now())) returning * into g;
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

-- Put my mark in a cell (0 to 8). With 3 marks already out, my oldest one vanishes first.
create or replace function public.ttt_move(p_cell int)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid; g public.ttt_games; mark text; b text; ln int[];
        mv int[]; mine int[]; i int;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_other is null then raise exception 'not_paired'; end if;
  if exists (select 1 from public.partnerships where id = v_pid and unpair_by is not null) then raise exception 'unpair_pending'; end if;
  select * into g from public.ttt_games where partnership_id = v_pid for update;
  if not found or g.status <> 'playing' then raise exception 'no_game'; end if;
  if g.turn <> me then raise exception 'not_your_turn'; end if;
  -- The cell must be empty on the board as it is now, before anything vanishes.
  if p_cell is null or p_cell < 0 or p_cell > 8 or substr(g.board, p_cell + 1, 1) <> '.' then raise exception 'bad_cell'; end if;
  mark := case when g.x_player = me then 'X' else 'O' end;
  b := g.board;
  mv := public._ttt_order(g.board, g.moves);
  mine := public._ttt_mine(g.board, g.moves, mark);
  for i in 1..greatest(cardinality(mine) - 2, 0) loop
    b := overlay(b placing '.' from mine[i] + 1 for 1);
    mv := array_remove(mv, mine[i]);
  end loop;
  b := overlay(b placing mark from p_cell + 1 for 1);
  mv := mv || p_cell;
  ln := public._ttt_line(b);
  if ln is not null then
    update public.ttt_games
       set board = b, moves = mv, status = 'won', winner = me, line = ln, turn = null, updated_at = now(),
           score = jsonb_set(score, array[me::text], to_jsonb(coalesce((score ->> me::text)::int, 0) + 1))
     where partnership_id = v_pid returning * into g;
  else
    update public.ttt_games set board = b, moves = mv, turn = v_other, updated_at = now() where partnership_id = v_pid returning * into g;
  end if;
  return public._ttt_json(g, me);
end $$;

-- A new board after a game. The one who went second goes first. If the other player already started the new
-- game (an empty board, nobody has moved), this just returns it.
create or replace function public.ttt_again()
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid; g public.ttt_games;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_other is null then raise exception 'not_paired'; end if;
  if exists (select 1 from public.partnerships where id = v_pid and unpair_by is not null) then raise exception 'unpair_pending'; end if;
  select * into g from public.ttt_games where partnership_id = v_pid for update;
  if not found then raise exception 'no_game'; end if;
  if g.status = 'playing' and g.board = '.........' and cardinality(g.moves) = 0 then
    update public.ttt_games set seen = jsonb_set(seen, array[me::text], to_jsonb(now())) where partnership_id = v_pid returning * into g;
    return public._ttt_json(g, me);
  end if;
  if g.status not in ('won', 'draw') then raise exception 'no_game'; end if;
  update public.ttt_games
     set board = '.........', moves = '{}', status = 'playing', winner = null, line = null,
         x_player = o_player, o_player = x_player, turn = o_player, updated_at = now(),
         seen = jsonb_set(seen, array[me::text], to_jsonb(now()))
   where partnership_id = v_pid returning * into g;
  return public._ttt_json(g, me);
end $$;

-- ttt_state, ttt_quit are unchanged (ttt_quit deliberately has no unpair check: it must always work).

revoke all on function public.ttt_state(), public.ttt_play(), public.ttt_move(int), public.ttt_again(), public.ttt_quit() from public, anon;
grant execute on function public.ttt_state(), public.ttt_play(), public.ttt_move(int), public.ttt_again(), public.ttt_quit() to authenticated;
-- New internal helpers: not callable from the app.
revoke all on function public._ttt_order(text, int[]), public._ttt_mine(text, int[], text), public._ttt_fade(text, int[], text) from public, anon, authenticated;
