-- Tic-tac-toe between partners, for the break while a phone is locked.
--
-- One game per pair at a time. The board is nine characters ('.', 'X', 'O'), cell 0 top-left to 8 bottom-right.
-- - ttt_play(): join the partner's waiting game, or open one (and drop a "game_invite|ttt" line in the chat).
-- - ttt_move(cell): your turn only, an empty cell only. Works out a win (3 in a row) or a draw.
-- - ttt_again(): after a game, a new board; the other player goes first. The score carries on.
-- - ttt_quit(): ends it for both.
-- - ttt_state(): the game as I see it. Calling it marks me as "here", so the other phone can show that.
-- Idle games (30 min) and unanswered invites (10 min) are cleared. RLS is on with no policies: everything goes
-- through these SECURITY DEFINER functions, and only the two people of a partnership can touch their game.

create table if not exists public.ttt_games (
  partnership_id uuid primary key references public.partnerships(id) on delete cascade,
  board text not null default '.........' check (char_length(board) = 9),
  x_player uuid not null references auth.users(id) on delete cascade,
  o_player uuid references auth.users(id) on delete cascade,
  turn uuid,
  status text not null default 'waiting' check (status in ('waiting', 'playing', 'won', 'draw')),
  winner uuid,
  line int[],
  invited_by uuid references auth.users(id) on delete cascade,
  score jsonb not null default '{}'::jsonb,
  seen jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
alter table public.ttt_games enable row level security;

-- Three in a row on a board, as the cells, or null.
create or replace function public._ttt_line(p_board text)
returns int[] language plpgsql immutable set search_path to '' as $$
declare l int[]; c text;
begin
  foreach l slice 1 in array array[[0,1,2],[3,4,5],[6,7,8],[0,3,6],[1,4,7],[2,5,8],[0,4,8],[2,4,6]] loop
    c := substr(p_board, l[1] + 1, 1);
    if c <> '.' and c = substr(p_board, l[2] + 1, 1) and c = substr(p_board, l[3] + 1, 1) then return l; end if;
  end loop;
  return null;
end $$;

create or replace function public._ttt_json(g public.ttt_games, me uuid, p_note bigint default 0)
returns jsonb language plpgsql stable security definer set search_path to '' as $$
declare other uuid := case when g.x_player = me then g.o_player else g.x_player end;
begin
  return jsonb_build_object(
    'status', g.status,
    'board', g.board,
    'i_am', case when g.x_player = me then 'X' else 'O' end,
    'my_turn', g.status = 'playing' and g.turn = me,
    'winner', case when g.winner is null then null when g.winner = me then 'me' else 'them' end,
    'line', to_jsonb(g.line),
    'invited_by_me', g.invited_by = me,
    'other_here', other is not null and coalesce((g.seen ->> other::text)::timestamptz, 'epoch'::timestamptz) > now() - interval '9 seconds',
    'wins_me', coalesce((g.score ->> me::text)::int, 0),
    'wins_them', coalesce((g.score ->> coalesce(other::text, ''))::int, 0),
    'rev', (extract(epoch from g.updated_at) * 1000)::bigint,
    'note', p_note
  );
end $$;

-- The game as I see it.
create or replace function public.ttt_state()
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid; g public.ttt_games;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_other is null then return jsonb_build_object('status', 'none'); end if;
  select * into g from public.ttt_games where partnership_id = v_pid;
  if not found then return jsonb_build_object('status', 'none'); end if;
  if g.updated_at < now() - interval '30 minutes' or (g.status = 'waiting' and g.updated_at < now() - interval '10 minutes') then
    delete from public.ttt_games where partnership_id = v_pid;
    return jsonb_build_object('status', 'none');
  end if;
  update public.ttt_games set seen = jsonb_set(seen, array[me::text], to_jsonb(now())) where partnership_id = v_pid
  returning * into g;
  return public._ttt_json(g, me);
end $$;

-- Join the game my partner opened, or open one.
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
    insert into public.messages (partnership_id, sender, kind, body) values (v_pid, me, 'event', 'game_invite|ttt') returning id into v_mid;
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

-- Put my mark in a cell (0 to 8).
create or replace function public.ttt_move(p_cell int)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid; g public.ttt_games; mark text; b text; ln int[];
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_other is null then raise exception 'not_paired'; end if;
  select * into g from public.ttt_games where partnership_id = v_pid for update;
  if not found or g.status <> 'playing' then raise exception 'no_game'; end if;
  if g.turn <> me then raise exception 'not_your_turn'; end if;
  if p_cell is null or p_cell < 0 or p_cell > 8 or substr(g.board, p_cell + 1, 1) <> '.' then raise exception 'bad_cell'; end if;
  mark := case when g.x_player = me then 'X' else 'O' end;
  b := overlay(g.board placing mark from p_cell + 1 for 1);
  ln := public._ttt_line(b);
  if ln is not null then
    update public.ttt_games
       set board = b, status = 'won', winner = me, line = ln, turn = null, updated_at = now(),
           score = jsonb_set(score, array[me::text], to_jsonb(coalesce((score ->> me::text)::int, 0) + 1))
     where partnership_id = v_pid returning * into g;
  elsif position('.' in b) = 0 then
    update public.ttt_games set board = b, status = 'draw', turn = null, updated_at = now() where partnership_id = v_pid returning * into g;
  else
    update public.ttt_games set board = b, turn = v_other, updated_at = now() where partnership_id = v_pid returning * into g;
  end if;
  return public._ttt_json(g, me);
end $$;

-- A new board after a game. The one who went second goes first.
create or replace function public.ttt_again()
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid; v_other uuid; g public.ttt_games;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
  if v_other is null then raise exception 'not_paired'; end if;
  select * into g from public.ttt_games where partnership_id = v_pid for update;
  if not found or g.status not in ('won', 'draw') then raise exception 'no_game'; end if;
  update public.ttt_games
     set board = '.........', status = 'playing', winner = null, line = null,
         x_player = o_player, o_player = x_player, turn = o_player, updated_at = now(),
         seen = jsonb_set(seen, array[me::text], to_jsonb(now()))
   where partnership_id = v_pid returning * into g;
  return public._ttt_json(g, me);
end $$;

-- End the game for both of us.
create or replace function public.ttt_quit()
returns boolean language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_pid uuid;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  select pr.pid into v_pid from public._pair_of(me) pr;
  if v_pid is not null then delete from public.ttt_games where partnership_id = v_pid; end if;
  return true;
end $$;

revoke all on function public.ttt_state(), public.ttt_play(), public.ttt_move(int), public.ttt_again(), public.ttt_quit() from public, anon;
grant execute on function public.ttt_state(), public.ttt_play(), public.ttt_move(int), public.ttt_again(), public.ttt_quit() to authenticated;
