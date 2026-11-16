-- Tests for 20261114_tictactoe.sql + 20261115_tictactoe_vanish.sql (vanishing-marks tic-tac-toe).
-- Run against a database where both migrations are applied (a Supabase branch or a local Postgres with the auth stub):
--   psql -v ON_ERROR_STOP=1 -f supabase/tests/ttt_vanish_test.sql
-- Everything happens in one transaction that is rolled back at the end, so it leaves nothing behind.
-- Each check prints "NOTICE:  PASS name"; the first failure raises an exception and stops the script.
-- Users: A (owner) and B (partner) are paired; C and D are a second pair (C is the "stranger"); E is not paired.

begin;
create schema tt;
grant usage on schema tt to authenticated, anon;

create function tt.u(l text) returns uuid language sql immutable as $$ select md5('tt-' || l)::uuid $$;
create function tt.as_user(l text) returns void language sql as $$ select set_config('request.jwt.claim.sub', tt.u(l)::text, true) $$;
create function tt.ok(n text, c boolean) returns void language plpgsql as $$
begin if c is not true then raise exception 'FAIL %', n; end if; raise notice 'PASS %', n; end $$;
create function tt.eq(n text, got text, want text) returns void language plpgsql as $$
begin
  if got is distinct from want then raise exception 'FAIL %: got % want %', n, coalesce(got, '<null>'), coalesce(want, '<null>'); end if;
  raise notice 'PASS %', n;
end $$;
-- The error message a statement raises, or null if it succeeds.
create function tt.err(q text) returns text language plpgsql as $$
begin execute q; return null; exception when others then return sqlerrm; end $$;
-- Superuser-side helpers (SECURITY DEFINER, owned by the script runner) for setting up positions and peeking at rows.
create function tt.setpos(p_board text, p_moves int[], p_turn_x boolean) returns void language sql security definer as $$
  update public.ttt_games set board = p_board, moves = p_moves, status = 'playing', winner = null, line = null,
         turn = case when p_turn_x then x_player else o_player end
   where partnership_id = (select id from public.partnerships where owner = tt.u('A'));
$$;
create function tt.moves() returns text language sql security definer as $$
  select moves::text from public.ttt_games where partnership_id = (select id from public.partnerships where owner = tt.u('A')) $$;
create function tt.games() returns int language sql security definer as $$ select count(*)::int from public.ttt_games $$;
create function tt.invites(l text) returns int language sql security definer as $$
  select count(*)::int from public.messages where sender = tt.u(l) and kind = 'event' and body = 'game_invite|ttt' $$;
grant execute on function tt.u(text), tt.as_user(text), tt.ok(text, boolean), tt.eq(text, text, text), tt.err(text),
  tt.setpos(text, int[], boolean), tt.moves(), tt.games(), tt.invites(text) to authenticated, anon;

insert into auth.users(id) select tt.u(l) from unnest(array['A','B','C','D','E']) l;
insert into public.partnerships(owner, partner) values (tt.u('A'), tt.u('B')), (tt.u('C'), tt.u('D'));

-- 0. Schema / contract -----------------------------------------------------------------------------------------
do $$ begin
  perform tt.ok('moves column exists, int[] not null default empty', exists (
    select 1 from information_schema.columns where table_schema = 'public' and table_name = 'ttt_games' and column_name = 'moves'
       and is_nullable = 'NO' and data_type = 'ARRAY' and column_default like '%{}%'));
  perform tt.ok('draw is still allowed by the status check', pg_get_constraintdef((
    select oid from pg_constraint where conrelid = 'public.ttt_games'::regclass and contype = 'c' and pg_get_constraintdef(oid) like '%draw%')) like '%draw%');
  perform tt.ok('anon cannot execute ttt_state', not has_function_privilege('anon', 'public.ttt_state()', 'execute'));
  perform tt.ok('authenticated can execute all five RPCs',
    has_function_privilege('authenticated', 'public.ttt_state()', 'execute') and has_function_privilege('authenticated', 'public.ttt_play()', 'execute')
    and has_function_privilege('authenticated', 'public.ttt_move(int)', 'execute') and has_function_privilege('authenticated', 'public.ttt_again()', 'execute')
    and has_function_privilege('authenticated', 'public.ttt_quit()', 'execute'));
  perform tt.ok('helpers are not callable by authenticated', not has_function_privilege('authenticated', 'public._ttt_fade(text,int[],text)', 'execute'));
end $$;

-- 1. Auth and pairing errors ----------------------------------------------------------------------------------
set local role authenticated;
do $$
begin
  perform set_config('request.jwt.claim.sub', '', true);
  perform tt.eq('state: not_signed_in', tt.err('select public.ttt_state()'), 'not_signed_in');
  perform tt.eq('play: not_signed_in', tt.err('select public.ttt_play()'), 'not_signed_in');
  perform tt.eq('move: not_signed_in', tt.err('select public.ttt_move(0)'), 'not_signed_in');
  perform tt.eq('again: not_signed_in', tt.err('select public.ttt_again()'), 'not_signed_in');
  perform tt.eq('quit: not_signed_in', tt.err('select public.ttt_quit()'), 'not_signed_in');
  perform tt.as_user('E');
  perform tt.eq('unpaired state is none', public.ttt_state() ->> 'status', 'none');
  perform tt.eq('unpaired play: not_paired', tt.err('select public.ttt_play()'), 'not_paired');
  perform tt.eq('unpaired move: not_paired', tt.err('select public.ttt_move(0)'), 'not_paired');
  perform tt.eq('unpaired again: not_paired', tt.err('select public.ttt_again()'), 'not_paired');
end $$;
reset role;
set local role anon;
do $$ begin perform tt.ok('anon is refused', tt.err('select public.ttt_state()') like 'permission denied%'); end $$;
reset role;

-- 2. Invite and join --------------------------------------------------------------------------------------------
set local role authenticated;
do $$
declare j jsonb;
begin
  perform tt.as_user('A');
  perform tt.eq('no game yet', public.ttt_state() ->> 'status', 'none');
  perform tt.eq('move with no game: no_game', tt.err('select public.ttt_move(0)'), 'no_game');
  perform tt.eq('again with no game: no_game', tt.err('select public.ttt_again()'), 'no_game');
  j := public.ttt_play();
  perform tt.eq('A opens: waiting', j ->> 'status', 'waiting');
  perform tt.eq('A is X', j ->> 'i_am', 'X');
  perform tt.eq('A invited_by_me', j ->> 'invited_by_me', 'true');
  perform tt.ok('invite note id is set', (j ->> 'note')::bigint > 0);
  perform tt.eq('JSON keys (contract)', (select string_agg(k, ',' order by k) from jsonb_object_keys(j) k),
    'board,fade_me,fade_them,i_am,invited_by_me,line,my_turn,note,other_here,rev,rule,status,winner,wins_me,wins_them');
  perform tt.eq('rule is vanish', j ->> 'rule', 'vanish');
  perform tt.eq('fade_me null on empty board', j ->> 'fade_me', null);
  perform tt.eq('moves starts empty', tt.moves(), '{}');
  perform tt.eq('move while waiting: no_game', tt.err('select public.ttt_move(0)'), 'no_game');
  perform tt.eq('A playing again while waiting returns the same game', public.ttt_play() ->> 'status', 'waiting');
  perform tt.eq('...without a second invite', tt.invites('A')::text, '1');
  perform tt.as_user('B');
  j := public.ttt_state();
  perform tt.eq('B sees waiting', j ->> 'status', 'waiting');
  perform tt.eq('B did not invite', j ->> 'invited_by_me', 'false');
  j := public.ttt_play();
  perform tt.eq('B joins: playing', j ->> 'status', 'playing');
  perform tt.eq('B is O', j ->> 'i_am', 'O');
  perform tt.eq('B is not first', j ->> 'my_turn', 'false');
  perform tt.eq('join adds no invite note', j ->> 'note', '0');
  perform tt.as_user('A');
  perform tt.eq('A is first', public.ttt_state() ->> 'my_turn', 'true');
end $$;

-- 3. Move errors and a normal win -----------------------------------------------------------------------------
do $$
declare j jsonb;
begin
  perform tt.as_user('B');
  perform tt.eq('out of turn: not_your_turn', tt.err('select public.ttt_move(4)'), 'not_your_turn');
  perform tt.as_user('A');
  perform tt.eq('cell 9: bad_cell', tt.err('select public.ttt_move(9)'), 'bad_cell');
  perform tt.eq('cell -1: bad_cell', tt.err('select public.ttt_move(-1)'), 'bad_cell');
  perform tt.eq('cell null: bad_cell', tt.err('select public.ttt_move(null)'), 'bad_cell');
  j := public.ttt_move(4);
  perform tt.eq('A plays 4', j ->> 'board', '....X....');
  perform tt.eq('turn passes', j ->> 'my_turn', 'false');
  perform tt.eq('A cannot move twice', tt.err('select public.ttt_move(0)'), 'not_your_turn');
  perform tt.as_user('B');
  perform tt.eq('taken cell: bad_cell', tt.err('select public.ttt_move(4)'), 'bad_cell');
  perform tt.eq('failed move did not change moves', tt.moves(), '{4}');
  perform public.ttt_move(0);            -- B: O at 0
  perform tt.as_user('A'); perform public.ttt_move(2);
  perform tt.as_user('B'); perform public.ttt_move(1);
  perform tt.as_user('A'); j := public.ttt_move(6);   -- X: 4,2,6 -> diagonal 2,4,6
  perform tt.eq('normal win: status', j ->> 'status', 'won');
  perform tt.eq('normal win: winner me', j ->> 'winner', 'me');
  perform tt.eq('normal win: line', j ->> 'line', '[2, 4, 6]');
  perform tt.eq('normal win: score', j ->> 'wins_me', '1');
  perform tt.eq('normal win: no fade after the game', j ->> 'fade_me', null);
  perform tt.eq('normal win: moves recorded', tt.moves(), '{4,0,2,1,6}');
  perform tt.eq('no moves after a win', tt.err('select public.ttt_move(8)'), 'no_game');
  perform tt.as_user('B');
  j := public.ttt_state();
  perform tt.eq('loser sees winner them', j ->> 'winner', 'them');
  perform tt.eq('loser sees score', j ->> 'wins_them', '1');
end $$;

-- 4. Play again: swaps starter, keeps score, idempotent ----------------------------------------------------------
do $$
declare j jsonb;
begin
  perform tt.as_user('B');
  j := public.ttt_again();
  perform tt.eq('again: playing', j ->> 'status', 'playing');
  perform tt.eq('again: empty board', j ->> 'board', '.........');
  perform tt.eq('again: loser B goes first', j ->> 'my_turn', 'true');
  perform tt.eq('again: B is X now', j ->> 'i_am', 'X');
  perform tt.eq('again: score kept (B)', j ->> 'wins_me', '0');
  perform tt.eq('again: score kept (A)', j ->> 'wins_them', '1');
  perform tt.eq('again: moves reset', tt.moves(), '{}');
  perform tt.eq('again: winner cleared', j ->> 'winner', null);
  perform tt.eq('again: line cleared', j ->> 'line', null);
  perform tt.as_user('A');
  j := public.ttt_again();     -- the other player taps it a moment later
  perform tt.eq('double again: no error, same game', j ->> 'status', 'playing');
  perform tt.eq('double again: did not swap back (A is O)', j ->> 'i_am', 'O');
  perform tt.eq('double again: A waits', j ->> 'my_turn', 'false');
  perform tt.eq('double again: score intact', j ->> 'wins_me', '1');
  perform tt.eq('third again is still fine', public.ttt_again() ->> 'i_am', 'O');
  perform tt.as_user('B'); perform public.ttt_move(0);
  perform tt.eq('again once a move was made: no_game', tt.err('select public.ttt_again()'), 'no_game');
  perform tt.as_user('A');
  perform tt.eq('again mid-game for the other side: no_game', tt.err('select public.ttt_again()'), 'no_game');
end $$;

-- 5. The vanish rule, step by step. B is X (started), A is O. -----------------------------------------------------
do $$
declare j jsonb;
begin
  perform tt.as_user('B'); perform public.ttt_quit(); perform public.ttt_play();      -- fresh game, B is X
  perform tt.as_user('A'); perform public.ttt_play();                                   -- A joins as O
  perform tt.as_user('B'); j := public.ttt_move(0);
  perform tt.eq('1 mark: no fade', j ->> 'fade_me', null);
  perform tt.as_user('A'); perform public.ttt_move(4);
  perform tt.as_user('B'); perform public.ttt_move(1);
  perform tt.as_user('A'); perform public.ttt_move(8);
  perform tt.as_user('B'); j := public.ttt_move(3);                                     -- X: 0,1,3 (three marks)
  perform tt.eq('B has 3 marks: board', j ->> 'board', 'XX.XO...O');
  perform tt.eq('B has 3 marks: fade_me = oldest (0)', j ->> 'fade_me', '0');
  perform tt.eq('B has 3 marks: fade_them null (A has 2)', j ->> 'fade_them', null);
  perform tt.as_user('A');
  j := public.ttt_state();
  perform tt.eq('A sees fade_them 0', j ->> 'fade_them', '0');
  perform tt.eq('A fade_me null', j ->> 'fade_me', null);
  j := public.ttt_move(5);                                                              -- O: 4,8,5 (three marks); no line
  perform tt.eq('A has 3 marks, nothing vanished yet', j ->> 'board', 'XX.XOO..O');
  perform tt.eq('A fade_me = 4', j ->> 'fade_me', '4');
  perform tt.eq('A fade_them = 0', j ->> 'fade_them', '0');
  perform tt.eq('moves so far', tt.moves(), '{0,4,1,8,3,5}');
  perform tt.as_user('B');
  j := public.ttt_state();
  perform tt.eq('B sees fade_me 0', j ->> 'fade_me', '0');
  perform tt.eq('B sees fade_them 4', j ->> 'fade_them', '4');
  -- Own fading mark's cell is rejected (cell 0 is B's oldest), and so is any taken cell.
  perform tt.eq('own fading cell rejected', tt.err('select public.ttt_move(0)'), 'bad_cell');
  perform tt.eq('other taken cell rejected', tt.err('select public.ttt_move(4)'), 'bad_cell');
  perform tt.eq('rejected moves left moves alone', tt.moves(), '{0,4,1,8,3,5}');
  -- Naively 0,1,2 would be a line, but 0 vanishes with this move: no win.
  j := public.ttt_move(2);
  perform tt.eq('4th mark: oldest (0) removed', j ->> 'board', '.XXXOO..O');
  perform tt.eq('line only with the vanished mark is NOT a win', j ->> 'status', 'playing');
  perform tt.eq('...no winner', j ->> 'winner', null);
  perform tt.eq('...turn passed', j ->> 'my_turn', 'false');
  perform tt.eq('moves: vanished removed, new appended', tt.moves(), '{4,1,8,3,5,2}');
  perform tt.eq('fade_me moves on to the next oldest (1)', j ->> 'fade_me', '1');
  perform tt.eq('fade_them still 4', j ->> 'fade_them', '4');
  perform tt.eq('B score unchanged', j ->> 'wins_me', '0');
  -- A: the vanished cell 0 is free again; naive diagonal 0,4,8 would be a line, but 4 vanishes: no win.
  perform tt.as_user('A');
  perform tt.eq('A cannot play on own fading cell 4', tt.err('select public.ttt_move(4)'), 'bad_cell');
  j := public.ttt_move(0);
  perform tt.eq('A: oldest (4) removed, 0 placed', j ->> 'board', 'OXXX.O..O');
  perform tt.eq('diagonal that needed the vanished mark is NOT a win', j ->> 'status', 'playing');
  perform tt.eq('A moves', tt.moves(), '{1,8,3,5,2,0}');
  perform tt.eq('A fade_me = 8', j ->> 'fade_me', '8');
  perform tt.eq('A fade_them = 1', j ->> 'fade_them', '1');
  perform tt.eq('opponent marks never vanish on my move', substr(j ->> 'board', 2, 3), 'XXX');
end $$;

-- 6. Win created only after the removal -----------------------------------------------------------------------
do $$
declare j jsonb;
begin
  -- X (B) has 6 (oldest), 0, 1; O has 3, 4, 7. X plays 2: 6 vanishes, then 0,1,2 is a line.
  reset role;
  perform tt.setpos('XX.OO.XO.', '{6,3,0,4,1,7}', true);       -- X: 6,0,1 ; O: 3,4,7 ; 6 is oldest
  set local role authenticated;
  perform tt.as_user('B');
  j := public.ttt_state();
  perform tt.eq('setup: fade_me is the oldest X (6)', j ->> 'fade_me', '6');
  perform tt.eq('setup: fade_them is the oldest O (3)', j ->> 'fade_them', '3');
  perform tt.eq('setup: no early win', j ->> 'status', 'playing');
  j := public.ttt_move(2);
  perform tt.eq('win after removal: board', j ->> 'board', 'XXXOO..O.');
  perform tt.eq('win after removal: status', j ->> 'status', 'won');
  perform tt.eq('win after removal: line', j ->> 'line', '[0, 1, 2]');
  perform tt.eq('win after removal: moves', tt.moves(), '{3,0,4,1,7,2}');
  perform tt.eq('win after removal: score for B', j ->> 'wins_me', '1');
  perform tt.eq('win after removal: A score 0 (quit reset the row)', j ->> 'wins_them', '0');
  perform tt.eq('state after win has no fade', public.ttt_state() ->> 'fade_them', null);
end $$;

-- 7. A row from before this migration: a board but empty moves --------------------------------------------------
do $$
declare j jsonb;
begin
  reset role;
  -- A is O, B is X here? Use whoever is X: set the position and look at i_am.
  perform tt.setpos('XOX.OX...', '{}', true);                  -- X: 0,2,5  O: 1,4 ; X to move
  set local role authenticated;
  perform tt.as_user('B');
  j := public.ttt_state();
  perform tt.eq('legacy: state does not crash', j ->> 'status', 'playing');
  perform tt.eq('legacy: fade_me falls back to board order (0)', j ->> 'fade_me', '0');
  perform tt.eq('legacy: fade_them null (2 marks)', j ->> 'fade_them', null);
  j := public.ttt_move(8);                                    -- 0 vanishes, 2,5,8 is a column
  perform tt.eq('legacy: move works and vanishes board-order oldest', j ->> 'board', '.OX.OX..X');
  perform tt.eq('legacy: win detected after removal', j ->> 'status', 'won');
  perform tt.eq('legacy: moves normalised', tt.moves(), '{1,2,4,5,8}');
  -- Legacy game where a player has 4 marks (old rules allowed up to 5): they end with exactly 3.
  reset role;
  perform tt.setpos('XOX.OXX.O', '{}', true);                  -- X: 0,2,5,6  O: 1,4,8; pretend it is X's turn
  set local role authenticated;
  perform tt.as_user('B');
  j := public.ttt_state();
  perform tt.eq('legacy 4 marks: fade_me 0', j ->> 'fade_me', '0');
  j := public.ttt_move(3);                                    -- removes 0 and 2, keeps 5,6 plus 3
  perform tt.eq('legacy 4 marks: trimmed to 3 after the move', j ->> 'board', '.O.XOXX.O' );
  perform tt.eq('legacy 4 marks: moves', tt.moves(), '{1,4,5,6,8,3}');
  perform tt.eq('legacy 4 marks: no false win (3,5,6 is not a line)', j ->> 'status', 'playing');
  -- Again after a legacy draw still works.
  reset role;
  update public.ttt_games set status = 'draw', turn = null, board = 'XOXXOOOXX', moves = '{}';
  set local role authenticated;
  perform tt.as_user('A');
  j := public.ttt_again();
  perform tt.eq('legacy draw: again works', j ->> 'status', 'playing');
  perform tt.eq('legacy draw: empty board', j ->> 'board', '.........');
  perform tt.eq('legacy draw: moves empty', tt.moves(), '{}');
end $$;
reset role;

-- 8. Invite spam suppression ---------------------------------------------------------------------------------------
set local role authenticated;
do $$
declare j jsonb; a0 int;
begin
  perform tt.as_user('A'); perform public.ttt_quit();
  select tt.invites('A') into a0;
  j := public.ttt_play();
  perform tt.eq('quit then play: new game, waiting', j ->> 'status', 'waiting');
  perform tt.eq('quit then play: invite suppressed (note 0)', j ->> 'note', '0');
  perform tt.eq('quit then play: no new chat message', tt.invites('A')::text, a0::text);
  perform public.ttt_quit();
  j := public.ttt_play();
  perform tt.eq('spamming quit/play stays suppressed', j ->> 'note', '0');
  perform tt.eq('...still no new chat message', tt.invites('A')::text, a0::text);
  perform public.ttt_quit();
end $$;
reset role;
update public.messages set created_at = now() - interval '11 minutes' where body = 'game_invite|ttt';
set local role authenticated;
do $$
declare j jsonb; a0 int;
begin
  perform tt.as_user('A');
  select tt.invites('A') into a0;
  j := public.ttt_play();
  perform tt.ok('after 11 minutes a new invite is allowed', (j ->> 'note')::bigint > 0);
  perform tt.eq('...and it was written', tt.invites('A')::text, (a0 + 1)::text);
  perform public.ttt_quit();
  j := public.ttt_play();
  perform tt.eq('...but only one again right after', j ->> 'note', '0');
  perform public.ttt_quit();
  -- A different sender is not held back by A's recent invite (B's old one was backdated too).
  perform tt.as_user('B'); j := public.ttt_play();
  perform tt.ok('B opening a game gets its own invite', (j ->> 'note')::bigint > 0);
  perform public.ttt_quit();
end $$;
reset role;

-- 9. unpair_pending (same condition ttt_play uses) and quit always works -----------------------------------------------
set local role authenticated;
do $$
begin
  perform tt.as_user('A'); perform public.ttt_play();
  perform tt.as_user('B'); perform public.ttt_play();     -- playing
  perform tt.as_user('A'); perform public.ttt_move(4);
end $$;
reset role;
update public.partnerships set unpair_by = tt.u('B'), unpair_at = now() where owner = tt.u('A');
set local role authenticated;
do $$
begin
  perform tt.as_user('A');
  perform tt.eq('play with unpair pending', tt.err('select public.ttt_play()'), 'unpair_pending');
  perform tt.eq('move with unpair pending', tt.err('select public.ttt_move(0)'), 'unpair_pending');
  perform tt.eq('again with unpair pending', tt.err('select public.ttt_again()'), 'unpair_pending');
  perform tt.eq('state still readable with unpair pending', public.ttt_state() ->> 'status', 'playing');
  perform tt.eq('quit works with unpair pending', public.ttt_quit()::text, 'true');
  perform tt.eq('quit really ended it', public.ttt_state() ->> 'status', 'none');
  perform tt.as_user('B');
  perform tt.eq('quit again from the other side is harmless', public.ttt_quit()::text, 'true');
end $$;
reset role;
update public.partnerships set unpair_by = null, unpair_at = null;

-- 10. Quit from either side -----------------------------------------------------------------------------------------
set local role authenticated;
do $$
begin
  perform tt.as_user('A'); perform public.ttt_play();
  perform tt.as_user('B'); perform public.ttt_play();
  perform tt.eq('B quits a running game', public.ttt_quit()::text, 'true');
  perform tt.eq('B sees none', public.ttt_state() ->> 'status', 'none');
  perform tt.as_user('A');
  perform tt.eq('A sees none too', public.ttt_state() ->> 'status', 'none');
  perform tt.eq('A move after quit: no_game', tt.err('select public.ttt_move(0)'), 'no_game');
  perform tt.eq('A again after quit: no_game', tt.err('select public.ttt_again()'), 'no_game');
  perform public.ttt_play();
  perform tt.as_user('B'); perform public.ttt_play();
  perform tt.as_user('A');
  perform tt.eq('A quits a running game', public.ttt_quit()::text, 'true');
  perform tt.as_user('B');
  perform tt.eq('B sees none too', public.ttt_state() ->> 'status', 'none');
  perform tt.eq('quit with no game is fine', public.ttt_quit()::text, 'true');
  perform tt.as_user('E');
  perform tt.eq('quit when unpaired is fine', public.ttt_quit()::text, 'true');
end $$;

-- 11. A stranger cannot see or affect the game ---------------------------------------------------------------------------
do $$
declare j jsonb; g0 text;
begin
  perform tt.as_user('A'); perform public.ttt_play();
  perform tt.as_user('B'); perform public.ttt_play();
  perform tt.as_user('A'); perform public.ttt_move(4);
  g0 := tt.moves();
  perform tt.as_user('C');
  perform tt.eq('stranger state: none', public.ttt_state() ->> 'status', 'none');
  perform tt.eq('stranger move: no_game (own pair has none)', tt.err('select public.ttt_move(0)'), 'no_game');
  perform tt.eq('stranger again: no_game', tt.err('select public.ttt_again()'), 'no_game');
  perform tt.eq('stranger table select sees no rows (RLS)', (select count(*) from public.ttt_games)::text, '0');
  perform tt.eq('stranger cannot update the table (RLS)', tt.err('update public.ttt_games set board = ''XXXXXXXXX'''), null);
  perform tt.as_user('A');
  perform tt.eq('...A sees own board unchanged by that', public.ttt_state() ->> 'board', '....X....');
  perform tt.as_user('C');
  j := public.ttt_play();
  perform tt.eq('stranger opens their own game', j ->> 'status', 'waiting');
  perform tt.eq('stranger quit', public.ttt_quit()::text, 'true');
  perform tt.eq('A-B game survived stranger actions', tt.moves(), g0);
  perform tt.as_user('A');
  perform tt.eq('A-B game still playing', public.ttt_state() ->> 'status', 'playing');
  perform tt.eq('A-B board intact', public.ttt_state() ->> 'board', '....X....');
end $$;
reset role;

select 'ALL TESTS PASSED' as result;
rollback;
