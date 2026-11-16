-- LUDO, for a pair (two seats used, opposite corners) or for a crew (a table of four seats anyone in the crew can sit at).
-- Additive; nothing existing is changed except _live_ok, which now also lets a crew talk on its own live topic.
--
-- The board: 52 squares round a cross, numbered 0..51 clockwise. Seat s starts at square 13*s, so a token's square is
-- (13*s + p) % 52 for its progress p = 0..50; p = 51..55 is its own coloured lane and 56 is home. -1 is the yard, -2 a token
-- of someone who left the game. Safe squares: every start (s*13) and every star (s*13 + 8). Seats: 0 red (top left),
-- 1 green (top right), 2 yellow (bottom right), 3 blue (bottom left). A pair plays seats 0 and 2.
--
-- Rules: a 6 brings a token out; a 6, a capture or a token reaching home gives another roll; three 6s in a row lose the turn;
-- landing on a single opponent token off a safe square sends it to its yard (two of one colour on a square are a block and
-- can't be captured); home needs the exact roll. A turn where only one move is possible (or every choice is the same) plays
-- itself. The first to bring all four home wins; with more players the rest play on for the places.
--
-- The dice are rolled HERE, never on a phone. Every function returns the whole game as JSON (_ludo_json); it is the same for
-- everybody except me_seat, so the phones can also pass it to each other over the live channel for speed (the server stays the
-- truth: each phone fetches it again straight after).
-- A turn has 30 seconds. After that ludo_tick() (called by any phone at the table) plays it for the idle player: always in a
-- crew game, and in a pair game only while that player has the game open (otherwise it waits and they get a push).
-- Errors: not_signed_in, not_paired, unpair_pending, no_crew, bad_scope, no_game, not_your_turn, not_now, seat_taken,
-- need_two, not_host, bad_token.

create table if not exists public.ludo_games (
  id uuid primary key default gen_random_uuid(),
  scope text not null check (scope in ('pair', 'crew')),
  scope_id uuid not null,
  status text not null default 'lobby' check (status in ('lobby', 'playing', 'over')),
  host uuid not null references auth.users(id) on delete cascade,
  seats uuid[] not null default array[null, null, null, null]::uuid[],
  left_seats int[] not null default '{}',
  tokens int[] not null default array_fill(-1, array[16]),
  turn int not null default 0,
  phase text not null default 'roll' check (phase in ('roll', 'move')),
  dice int not null default 0,
  sixes int not null default 0,
  places int[] not null default '{}',
  last jsonb,
  seq bigint not null default 0,
  deadline timestamptz,
  seen jsonb not null default '{}'::jsonb,
  wins jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (scope, scope_id)
);
alter table public.ludo_games enable row level security;

-- ---------- The live channel, for crews too ----------

create or replace function public._live_ok(p_topic text)
returns boolean language sql stable security definer set search_path to '' as $$
  select (p_topic like 'live:%' and exists (
            select 1 from public.partnerships p
             where p.id::text = split_part(p_topic, ':', 2)
               and p.partner is not null
               and auth.uid() in (p.owner, p.partner)))
      or (p_topic like 'live:crew-%' and exists (
            select 1 from public.crew_members m
             where m.crew_id::text = substr(p_topic, 11)
               and m.user_id = auth.uid()))
$$;

create or replace function public.live_crew_topic()
returns text language plpgsql stable security definer set search_path to '' as $$
declare v_crew uuid;
begin
  if auth.uid() is null then raise exception 'not_signed_in'; end if;
  v_crew := public._crew_of(auth.uid());
  if v_crew is null then return null; end if;
  return 'live:crew-' || v_crew::text;
end $$;
revoke all on function public.live_crew_topic() from public;
grant execute on function public.live_crew_topic() to authenticated;

-- ---------- Board helpers ----------

create or replace function public._ludo_abs(p_seat int, p_pos int)
returns int language sql immutable set search_path to '' as $$
  select case when p_pos between 0 and 50 then (p_seat * 13 + p_pos) % 52 else null end
$$;

create or replace function public._ludo_safe(p_abs int)
returns boolean language sql immutable set search_path to '' as $$
  select p_abs is not null and (p_abs % 13 = 0 or p_abs % 13 = 8)
$$;

-- Where a token at p_pos goes with p_dice, or null if it can't move.
create or replace function public._ludo_dest(p_pos int, p_dice int)
returns int language sql immutable set search_path to '' as $$
  select case
    when p_pos = -1 then case when p_dice = 6 then 0 end
    when p_pos between 0 and 55 and p_pos + p_dice <= 56 then p_pos + p_dice
  end
$$;

-- The tokens (0..15) a token of p_seat landing on p_to would capture.
create or replace function public._ludo_caps(p_tokens int[], p_seats uuid[], p_seat int, p_to int)
returns int[] language plpgsql immutable set search_path to '' as $$
declare a int := public._ludo_abs(p_seat, p_to); res int[] := '{}'; s int; j int; n int; hit int;
begin
  if a is null or public._ludo_safe(a) then return res; end if;
  for s in 0..3 loop
    if s = p_seat or p_seats[s + 1] is null then continue; end if;
    n := 0; hit := null;
    for j in 0..3 loop
      if public._ludo_abs(s, p_tokens[s * 4 + j + 1]) = a then n := n + 1; hit := s * 4 + j; end if;
    end loop;
    if n = 1 then res := res || hit; end if;
  end loop;
  return res;
end $$;

-- The tokens (0..3) the player on turn can move with the dice.
create or replace function public._ludo_movable(g public.ludo_games)
returns int[] language plpgsql immutable set search_path to '' as $$
declare res int[] := '{}'; k int;
begin
  for k in 0..3 loop
    if public._ludo_dest(g.tokens[g.turn * 4 + k + 1], g.dice) is not null then res := res || k; end if;
  end loop;
  return res;
end $$;

-- The next seat after p_from that is still playing.
create or replace function public._ludo_next(g public.ludo_games, p_from int)
returns int language plpgsql immutable set search_path to '' as $$
declare i int; s int;
begin
  for i in 1..4 loop
    s := (p_from + i) % 4;
    if g.seats[s + 1] is not null and not (s = any(g.left_seats)) and not (s = any(g.places)) then return s; end if;
  end loop;
  return p_from;
end $$;

-- How good a move is, for a turn played on someone's behalf: capture, then home, then out of the yard, then safety, then
-- the furthest token.
create or replace function public._ludo_best(g public.ludo_games, p_moves int[])
returns int language plpgsql immutable set search_path to '' as $$
declare k int; best int := p_moves[1]; best_score int := -1; sc int; frm int; dest int;
begin
  foreach k in array p_moves loop
    frm := g.tokens[g.turn * 4 + k + 1];
    dest := public._ludo_dest(frm, g.dice);
    sc := dest;
    if cardinality(public._ludo_caps(g.tokens, g.seats, g.turn, dest)) > 0 then sc := sc + 400; end if;
    if dest = 56 then sc := sc + 300; end if;
    if frm = -1 then sc := sc + 200; end if;
    if dest > 50 or public._ludo_safe(public._ludo_abs(g.turn, dest)) then sc := sc + 100; end if;
    if sc > best_score then best_score := sc; best := k; end if;
  end loop;
  return best;
end $$;

-- Plays token k of the player on turn with the dice already rolled.
create or replace function public._ludo_apply(g public.ludo_games, k int, p_rolled boolean, p_auto boolean)
returns public.ludo_games language plpgsql set search_path to '' as $$
declare seat int := g.turn; t int[] := g.tokens; frm int; dest int; caps int[]; c int; capj jsonb := '[]'::jsonb;
        done boolean; bonus boolean := false; active int[]; s int;
begin
  frm := t[seat * 4 + k + 1];
  dest := public._ludo_dest(frm, g.dice);
  if dest is null then raise exception 'bad_token'; end if;
  caps := public._ludo_caps(t, g.seats, seat, dest);
  foreach c in array caps loop
    capj := capj || jsonb_build_object('t', c, 'from', t[c + 1]);
    t[c + 1] := -1;
  end loop;
  t[seat * 4 + k + 1] := dest;
  g.tokens := t;
  done := t[seat * 4 + 1] = 56 and t[seat * 4 + 2] = 56 and t[seat * 4 + 3] = 56 and t[seat * 4 + 4] = 56;
  if done then g.places := g.places || seat; end if;
  active := '{}';
  for s in 0..3 loop
    if g.seats[s + 1] is not null and not (s = any(g.left_seats)) and not (s = any(g.places)) then active := active || s; end if;
  end loop;
  g.seq := g.seq + 1;
  if cardinality(active) <= 1 then
    if cardinality(active) = 1 then g.places := g.places || active[1]; end if;
    g.status := 'over';
    g.phase := 'roll';
    g.deadline := null;
    if g.seats[g.places[1] + 1] is not null then
      g.wins := jsonb_set(g.wins, array[g.seats[g.places[1] + 1]::text],
                          to_jsonb(coalesce((g.wins ->> g.seats[g.places[1] + 1]::text)::int, 0) + 1));
    end if;
  else
    bonus := not done and (g.dice = 6 or cardinality(caps) > 0 or dest = 56);
    if not bonus then
      g.turn := public._ludo_next(g, seat);
      g.sixes := 0;
    end if;
    g.phase := 'roll';
    g.deadline := now() + interval '30 seconds';
  end if;
  g.last := jsonb_build_object('kind', 'move', 'seat', seat, 'token', k, 'from', frm, 'to', dest, 'roll', g.dice,
                               'rolled', p_rolled, 'caps', capj, 'home', dest = 56, 'done', done, 'bonus', bonus,
                               'auto', p_auto, 'over', g.status = 'over', 'seq', g.seq);
  return g;
end $$;

-- Rolls for the player on turn. A turn with one possible move (or only identical ones), or one played on someone's behalf,
-- moves straight away.
create or replace function public._ludo_roll(g public.ludo_games, p_auto boolean)
returns public.ludo_games language plpgsql volatile set search_path to '' as $$
declare d int := 1 + floor(random() * 6)::int; mv int[]; seat int := g.turn; kinds int;
begin
  g.dice := d;
  g.sixes := case when d = 6 then g.sixes + 1 else 0 end;
  if g.sixes >= 3 then
    g.seq := g.seq + 1;
    g.last := jsonb_build_object('kind', 'roll', 'seat', seat, 'roll', d, 'three', true, 'auto', p_auto, 'seq', g.seq);
    g.sixes := 0;
    g.turn := public._ludo_next(g, seat);
    g.phase := 'roll';
    g.deadline := now() + interval '30 seconds';
    return g;
  end if;
  mv := public._ludo_movable(g);
  if cardinality(mv) = 0 then
    g.seq := g.seq + 1;
    g.last := jsonb_build_object('kind', 'roll', 'seat', seat, 'roll', d, 'none', true, 'auto', p_auto, 'seq', g.seq);
    if d <> 6 then
      g.turn := public._ludo_next(g, seat);
      g.sixes := 0;
    end if;
    g.phase := 'roll';
    g.deadline := now() + interval '30 seconds';
    return g;
  end if;
  select count(distinct g.tokens[seat * 4 + m + 1]) into kinds from unnest(mv) m;
  if kinds = 1 or p_auto then
    return public._ludo_apply(g, case when p_auto then public._ludo_best(g, mv) else mv[1] end, true, p_auto);
  end if;
  g.phase := 'move';
  g.seq := g.seq + 1;
  g.last := jsonb_build_object('kind', 'roll', 'seat', seat, 'roll', d, 'auto', p_auto, 'seq', g.seq);
  g.deadline := now() + interval '30 seconds';
  return g;
end $$;

-- ---------- Who, where ----------

-- My pair or my crew, for p_scope.
create or replace function public._ludo_sid(me uuid, p_scope text)
returns uuid language plpgsql stable security definer set search_path to '' as $$
declare v_pid uuid; v_other uuid; v_crew uuid;
begin
  if me is null then raise exception 'not_signed_in'; end if;
  if p_scope = 'pair' then
    select pr.pid, pr.other into v_pid, v_other from public._pair_of(me) pr;
    if v_other is null then raise exception 'not_paired'; end if;
    return v_pid;
  elsif p_scope = 'crew' then
    v_crew := public._crew_of(me);
    if v_crew is null then raise exception 'no_crew'; end if;
    return v_crew;
  end if;
  raise exception 'bad_scope';
end $$;

-- People who have left the crew lose their seat (and, mid-game, their tokens).
create or replace function public._ludo_tidy(g public.ludo_games)
returns public.ludo_games language plpgsql set search_path to '' as $$
declare s int; t int[] := g.tokens; j int; changed boolean := false; seats uuid[] := g.seats;
begin
  if g.scope <> 'crew' then return g; end if;
  for s in 0..3 loop
    if seats[s + 1] is not null and not exists (select 1 from public.crew_members where crew_id = g.scope_id and user_id = seats[s + 1]) then
      if g.status = 'lobby' then
        seats[s + 1] := null;
      elsif not (s = any(g.left_seats)) and not (s = any(g.places)) then
        g.left_seats := g.left_seats || s;
        for j in 0..3 loop if t[s * 4 + j + 1] < 56 then t[s * 4 + j + 1] := -2; end if; end loop;
      end if;
      changed := true;
    end if;
  end loop;
  g.seats := seats;
  g.tokens := t;
  if changed and g.status = 'playing' then g := public._ludo_settle(g); end if;
  return g;
end $$;

-- After someone left: end the game if one player is left, and move the turn on if it was theirs.
create or replace function public._ludo_settle(g public.ludo_games)
returns public.ludo_games language plpgsql set search_path to '' as $$
declare active int[] := '{}'; s int;
begin
  for s in 0..3 loop
    if g.seats[s + 1] is not null and not (s = any(g.left_seats)) and not (s = any(g.places)) then active := active || s; end if;
  end loop;
  if cardinality(active) <= 1 then
    if cardinality(active) = 1 then g.places := g.places || active[1]; end if;
    g.status := 'over';
    g.deadline := null;
  elsif not (g.turn = any(active)) then
    g.turn := public._ludo_next(g, g.turn);
    g.phase := 'roll';
    g.sixes := 0;
    g.deadline := now() + interval '30 seconds';
  end if;
  g.seq := g.seq + 1;
  g.last := jsonb_build_object('kind', 'left', 'seq', g.seq, 'over', g.status = 'over');
  return g;
end $$;

create or replace function public._ludo_json(g public.ludo_games, me uuid, p_note bigint default 0, p_crew_note bigint default 0)
returns jsonb language plpgsql stable security definer set search_path to '' as $$
declare seats jsonb := '[]'::jsonb; s int; u uuid; my int := -1;
begin
  for s in 0..3 loop
    u := g.seats[s + 1];
    if u = me then my := s; end if;
    seats := seats || jsonb_build_object(
      'seat', s,
      'id', u,
      'name', case when u is null then null else public._crew_name_of(u) end,
      'here', u is not null and coalesce((g.seen ->> u::text)::timestamptz, 'epoch'::timestamptz) > now() - interval '12 seconds',
      'left', s = any(g.left_seats),
      'place', coalesce(array_position(g.places, s), 0),
      'wins', case when u is null then 0 else coalesce((g.wins ->> u::text)::int, 0) end
    );
  end loop;
  return jsonb_build_object(
    'id', g.id,
    'scope', g.scope,
    'status', g.status,
    'host', g.host,
    'seats', seats,
    'tokens', to_jsonb(g.tokens),
    'turn', g.turn,
    'phase', g.phase,
    'dice', g.dice,
    'sixes', g.sixes,
    'last', g.last,
    'seq', g.seq,
    'left_ms', case when g.deadline is null then 0 else greatest(0, (extract(epoch from (g.deadline - now())) * 1000)::bigint) end,
    'me_seat', my,
    'rev', (extract(epoch from g.updated_at) * 1000)::bigint,
    'note', p_note,
    'crew_note', p_crew_note
  );
end $$;

-- The game row for me and p_scope, locked for change, or raises no_game. Old games are cleared away.
create or replace function public._ludo_get(me uuid, p_scope text)
returns public.ludo_games language plpgsql security definer set search_path to '' as $$
declare v_sid uuid := public._ludo_sid(me, p_scope); g public.ludo_games;
begin
  select * into g from public.ludo_games where scope = p_scope and scope_id = v_sid for update;
  if not found then raise exception 'no_game'; end if;
  if g.updated_at < now() - interval '2 days' then
    delete from public.ludo_games where id = g.id;
    raise exception 'no_game';
  end if;
  return g;
end $$;

-- Saves g (my "here" mark included) and returns it.
create or replace function public._ludo_save(g public.ludo_games, me uuid)
returns public.ludo_games language plpgsql security definer set search_path to '' as $$
begin
  update public.ludo_games set
    status = g.status, host = g.host, seats = g.seats, left_seats = g.left_seats, tokens = g.tokens, turn = g.turn,
    phase = g.phase, dice = g.dice, sixes = g.sixes, places = g.places, last = g.last, seq = g.seq, deadline = g.deadline,
    wins = g.wins, seen = jsonb_set(seen, array[me::text], to_jsonb(now())), updated_at = now()
  where id = g.id returning * into g;
  return g;
end $$;

-- In a pair game: tell my partner when the turn has come to them (or the game ended) and they are not looking. The id of
-- the hidden chat line, for the push.
create or replace function public._ludo_ping(old_turn uuid, g public.ludo_games, me uuid)
returns bigint language plpgsql security definer set search_path to '' as $$
declare v_other uuid; v_id bigint := 0; v_what text;
begin
  if g.scope <> 'pair' then return 0; end if;
  v_other := case when g.seats[1] = me then g.seats[3] else g.seats[1] end;
  if v_other is null then return 0; end if;
  if coalesce((g.seen ->> v_other::text)::timestamptz, 'epoch'::timestamptz) > now() - interval '12 seconds' then return 0; end if;
  if g.status = 'over' then v_what := case when g.seats[g.places[1] + 1] = me then 'won' else 'lost' end;
  elsif g.status = 'playing' and g.seats[g.turn + 1] = v_other and old_turn is distinct from v_other then v_what := 'turn';
  else return 0;
  end if;
  insert into public.messages (partnership_id, sender, kind, body)
  values (g.scope_id, me, 'event', 'game_move|ludo|' || v_what) returning id into v_id;
  return v_id;
end $$;

-- ---------- What the phones call ----------

-- The game for p_scope ('pair' or 'crew'), marking me as here; {"status": "none"} when there is none.
create or replace function public.ludo_state(p_scope text)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); g public.ludo_games;
begin
  begin
    g := public._ludo_get(me, p_scope);
  exception when others then
    if sqlerrm = 'no_game' then return jsonb_build_object('status', 'none'); end if;
    raise;
  end;
  g := public._ludo_tidy(g);
  g := public._ludo_save(g, me);
  return public._ludo_json(g, me);
end $$;

-- A look that does not mark me as here (for the chat's card).
create or replace function public.ludo_peek(p_scope text)
returns jsonb language plpgsql stable security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_sid uuid; g public.ludo_games;
begin
  v_sid := public._ludo_sid(me, p_scope);
  select * into g from public.ludo_games where scope = p_scope and scope_id = v_sid;
  if not found or g.updated_at < now() - interval '2 days' then return jsonb_build_object('status', 'none'); end if;
  return public._ludo_json(g, me);
end $$;

-- Opens a game, or returns the one there is. A pair game starts at once (me red, my partner yellow, a random first turn)
-- and my partner gets an invite in the chat. A crew game opens a table with me in the red seat; the crew is told.
create or replace function public.ludo_open(p_scope text)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); v_sid uuid := public._ludo_sid(me, p_scope); v_other uuid; g public.ludo_games;
        v_note bigint := 0; v_crew_note bigint := 0;
begin
  if p_scope = 'pair' then
    if exists (select 1 from public.partnerships where id = v_sid and unpair_by is not null) then raise exception 'unpair_pending'; end if;
    select pr.other into v_other from public._pair_of(me) pr;
  end if;
  select * into g from public.ludo_games where scope = p_scope and scope_id = v_sid for update;
  if found and g.updated_at < now() - interval '2 days' then
    delete from public.ludo_games where id = g.id;
  elsif found then
    g := public._ludo_tidy(g);
    g := public._ludo_save(g, me);
    return public._ludo_json(g, me);
  end if;
  if p_scope = 'pair' then
    insert into public.ludo_games (scope, scope_id, status, host, seats, turn, deadline, seen, seq, last)
    values ('pair', v_sid, 'playing', me, array[me, null, v_other, null]::uuid[],
            case when random() < 0.5 then 0 else 2 end, now() + interval '30 seconds',
            jsonb_build_object(me::text, now()), 1, null)
    returning * into g;
    update public.ludo_games set last = jsonb_build_object('kind', 'start', 'first', turn, 'seq', 1) where id = g.id returning * into g;
    insert into public.messages (partnership_id, sender, kind, body) values (v_sid, me, 'event', 'game_invite|ludo') returning id into v_note;
  else
    insert into public.ludo_games (scope, scope_id, status, host, seats, seen)
    values ('crew', v_sid, 'lobby', me, array[me, null, null, null]::uuid[], jsonb_build_object(me::text, now()))
    returning * into g;
    insert into public.crew_messages (crew_id, sender, kind, body) values (v_sid, me, 'event', 'ludo_open') returning id into v_crew_note;
  end if;
  return public._ludo_json(g, me, v_note, v_crew_note);
end $$;

-- Crew table: take seat p_seat (moving from another seat if I had one).
create or replace function public.ludo_sit(p_scope text, p_seat int)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); g public.ludo_games := public._ludo_get(me, p_scope); s uuid[];
begin
  if g.status <> 'lobby' then raise exception 'not_now'; end if;
  if p_seat not between 0 and 3 then raise exception 'seat_taken'; end if;
  s := g.seats;
  if s[p_seat + 1] is not null and s[p_seat + 1] <> me then raise exception 'seat_taken'; end if;
  s := array_replace(s, me, null);
  s[p_seat + 1] := me;
  g.seats := s;
  g.seq := g.seq + 1;
  g.last := jsonb_build_object('kind', 'sit', 'seat', p_seat, 'seq', g.seq);
  g := public._ludo_save(g, me);
  return public._ludo_json(g, me);
end $$;

-- Crew table: get up. The host getting up hands the table to whoever is left; an empty table is cleared away.
create or replace function public.ludo_stand(p_scope text)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); g public.ludo_games := public._ludo_get(me, p_scope); v_next uuid;
begin
  if g.status <> 'lobby' then raise exception 'not_now'; end if;
  g.seats := array_replace(g.seats, me, null);
  select x into v_next from unnest(g.seats) x where x is not null limit 1;
  if v_next is null then
    delete from public.ludo_games where id = g.id;
    return jsonb_build_object('status', 'none');
  end if;
  if g.host = me then g.host := v_next; end if;
  g.seq := g.seq + 1;
  g.last := jsonb_build_object('kind', 'stand', 'seq', g.seq);
  g := public._ludo_save(g, me);
  return public._ludo_json(g, me);
end $$;

-- Crew table: the host starts once two or more are seated. The first turn is picked at random.
create or replace function public.ludo_start(p_scope text)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); g public.ludo_games := public._ludo_get(me, p_scope); seated int[] := '{}'; s int; t int[];
begin
  if g.status <> 'lobby' then raise exception 'not_now'; end if;
  if g.host <> me then raise exception 'not_host'; end if;
  for s in 0..3 loop if g.seats[s + 1] is not null then seated := seated || s; end if; end loop;
  if cardinality(seated) < 2 then raise exception 'need_two'; end if;
  g.status := 'playing';
  t := array_fill(-1, array[16]);
  for s in 0..3 loop
    if g.seats[s + 1] is null then
      t[s * 4 + 1] := -2; t[s * 4 + 2] := -2; t[s * 4 + 3] := -2; t[s * 4 + 4] := -2;
    end if;
  end loop;
  g.tokens := t;
  g.left_seats := '{}';
  g.places := '{}';
  g.turn := seated[1 + floor(random() * cardinality(seated))::int];
  g.phase := 'roll';
  g.dice := 0;
  g.sixes := 0;
  g.deadline := now() + interval '30 seconds';
  g.seq := g.seq + 1;
  g.last := jsonb_build_object('kind', 'start', 'first', g.turn, 'seq', g.seq);
  g := public._ludo_save(g, me);
  return public._ludo_json(g, me);
end $$;

-- Roll the dice (my turn).
create or replace function public.ludo_roll(p_scope text)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); g public.ludo_games := public._ludo_get(me, p_scope); v_old uuid; v_note bigint;
begin
  if g.status <> 'playing' or g.phase <> 'roll' then raise exception 'not_now'; end if;
  if g.seats[g.turn + 1] is distinct from me then raise exception 'not_your_turn'; end if;
  v_old := g.seats[g.turn + 1];
  g := public._ludo_roll(g, false);
  g := public._ludo_save(g, me);
  v_note := public._ludo_ping(v_old, g, me);
  return public._ludo_json(g, me, v_note);
end $$;

-- Move token p_token (0..3) after a roll with a choice (my turn).
create or replace function public.ludo_move(p_scope text, p_token int)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); g public.ludo_games := public._ludo_get(me, p_scope); v_old uuid; v_note bigint;
begin
  if g.status <> 'playing' or g.phase <> 'move' then raise exception 'not_now'; end if;
  if g.seats[g.turn + 1] is distinct from me then raise exception 'not_your_turn'; end if;
  if p_token not between 0 and 3 or not (p_token = any(public._ludo_movable(g))) then raise exception 'bad_token'; end if;
  v_old := g.seats[g.turn + 1];
  g := public._ludo_apply(g, p_token, false, false);
  g := public._ludo_save(g, me);
  v_note := public._ludo_ping(v_old, g, me);
  return public._ludo_json(g, me, v_note);
end $$;

-- Plays the turn of someone who has let their 30 seconds run out (see the top). Anyone at the table may ask.
create or replace function public.ludo_tick(p_scope text)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); g public.ludo_games := public._ludo_get(me, p_scope); who uuid;
begin
  if not (me = any(g.seats)) then return public._ludo_json(g, me); end if;
  if g.status <> 'playing' or g.deadline is null or g.deadline > now() - interval '1 second' then
    return public._ludo_json(public._ludo_save(g, me), me);
  end if;
  who := g.seats[g.turn + 1];
  if g.scope = 'pair' and who <> me
     and coalesce((g.seen ->> who::text)::timestamptz, 'epoch'::timestamptz) < now() - interval '12 seconds' then
    -- They are away: wait for them (they were told it's their turn).
    return public._ludo_json(public._ludo_save(g, me), me);
  end if;
  if g.phase = 'roll' then
    g := public._ludo_roll(g, true);
  else
    g := public._ludo_apply(g, public._ludo_best(g, public._ludo_movable(g)), false, true);
  end if;
  g := public._ludo_save(g, me);
  return public._ludo_json(g, me);
end $$;

-- Play again with the same people. A crew table goes back to its seats so others can join first.
create or replace function public.ludo_again(p_scope text)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); g public.ludo_games := public._ludo_get(me, p_scope); s int; v_note bigint := 0; st uuid[];
begin
  if g.status <> 'over' then raise exception 'not_now'; end if;
  if not (me = any(g.seats)) then raise exception 'not_your_turn'; end if;
  -- Those who left the game lose their seat.
  st := g.seats;
  foreach s in array g.left_seats loop st[s + 1] := null; end loop;
  g.seats := st;
  g.left_seats := '{}';
  g.places := '{}';
  g.tokens := array_fill(-1, array[16]);
  g.dice := 0;
  g.sixes := 0;
  g.phase := 'roll';
  g.seq := g.seq + 1;
  if g.scope = 'pair' then
    g.status := 'playing';
    g.turn := case when random() < 0.5 then 0 else 2 end;
    g.deadline := now() + interval '30 seconds';
    g.last := jsonb_build_object('kind', 'start', 'first', g.turn, 'seq', g.seq);
  else
    g.status := 'lobby';
    g.host := me;
    g.deadline := null;
    g.last := jsonb_build_object('kind', 'again', 'seq', g.seq);
  end if;
  g := public._ludo_save(g, me);
  if g.scope = 'pair' then
    insert into public.messages (partnership_id, sender, kind, body) values (g.scope_id, me, 'event', 'game_move|ludo|again') returning id into v_note;
  end if;
  return public._ludo_json(g, me, v_note);
end $$;

-- Leave. A pair game ends for both. At a crew table: before it starts I just get up; mid-game my tokens leave the board and
-- the others play on; the host can end the whole table with p_end.
create or replace function public.ludo_leave(p_scope text, p_end boolean default false)
returns jsonb language plpgsql security definer set search_path to '' as $$
declare me uuid := auth.uid(); g public.ludo_games := public._ludo_get(me, p_scope); s int; j int; t int[]; v_host uuid;
begin
  if g.scope = 'pair' or (p_end and g.host = me) or (g.status = 'over' and not exists (
       select 1 from unnest(g.seats) x where x is not null and x <> me)) then
    delete from public.ludo_games where id = g.id;
    return jsonb_build_object('status', 'none');
  end if;
  if g.status = 'lobby' then return public.ludo_stand(p_scope); end if;
  s := array_position(g.seats, me) - 1;
  if s is null or s < 0 then return public._ludo_json(g, me); end if;
  if g.status = 'playing' and not (s = any(g.left_seats)) and not (s = any(g.places)) then
    t := g.tokens;
    for j in 0..3 loop if t[s * 4 + j + 1] < 56 then t[s * 4 + j + 1] := -2; end if; end loop;
    g.tokens := t;
    g.left_seats := g.left_seats || s;
    g := public._ludo_settle(g);
  end if;
  if g.host = me then
    select x into v_host from unnest(g.seats) x where x is not null and x <> me limit 1;
    g.host := coalesce(v_host, me);
  end if;
  g := public._ludo_save(g, me);
  return public._ludo_json(g, me);
end $$;

do $$
declare f text;
begin
  foreach f in array array[
    'ludo_state(text)', 'ludo_peek(text)', 'ludo_open(text)', 'ludo_sit(text, int)', 'ludo_stand(text)', 'ludo_start(text)',
    'ludo_roll(text)', 'ludo_move(text, int)', 'ludo_tick(text)', 'ludo_again(text)', 'ludo_leave(text, boolean)'
  ] loop
    execute format('revoke all on function public.%s from public', f);
    execute format('grant execute on function public.%s to authenticated', f);
  end loop;
  foreach f in array array[
    '_ludo_sid(uuid, text)', '_ludo_json(public.ludo_games, uuid, bigint, bigint)', '_ludo_get(uuid, text)',
    '_ludo_save(public.ludo_games, uuid)', '_ludo_ping(uuid, public.ludo_games, uuid)', '_ludo_tidy(public.ludo_games)',
    '_ludo_settle(public.ludo_games)', '_ludo_apply(public.ludo_games, int, boolean, boolean)',
    '_ludo_roll(public.ludo_games, boolean)'
  ] loop
    execute format('revoke all on function public.%s from public, anon, authenticated', f);
  end loop;
end $$;
