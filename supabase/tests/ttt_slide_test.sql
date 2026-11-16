-- Test for 20261119_ttt_slide.sql (sliding marks). Run where all tictactoe migrations are applied:
--   psql -v ON_ERROR_STOP=1 -f supabase/tests/ttt_slide_test.sql
-- It ends by raising 'ROLLBACK_OK <results>' on purpose, so nothing is left behind. Expected results:
--   B early slide=must_place; pings after A3rd=1; board=XX.OO..OX; A must_slide=true my_turn=true; A place with 3=must_slide;
--   slide their mark / onto occupied / same / off board = bad_cell; B out of turn=not_your_turn;
--   win status=won winner=me line=[0, 1, 2] last=8>2 board=XXXOO..O.; again status=playing board=.........
do $$
declare a uuid := md5('slide-A')::uuid; b uuid := md5('slide-B')::uuid; j jsonb; e text; pid uuid; out text := ''; n int;
begin
  insert into auth.users(id) values (a), (b);
  insert into public.partnerships(owner, partner) values (a, b) returning id into pid;
  perform set_config('request.jwt.claim.sub', a::text, true);
  j := public.ttt_play();
  perform set_config('request.jwt.claim.sub', b::text, true);
  j := public.ttt_play(); out := out || 'B joined rule=' || (j->>'rule') || '; ';
  perform set_config('request.jwt.claim.sub', a::text, true);
  j := public.ttt_move(0);
  perform set_config('request.jwt.claim.sub', b::text, true);
  begin perform public.ttt_slide(0, 1); exception when others then e := sqlerrm; end; out := out || 'B early slide=' || e || '; ';
  j := public.ttt_move(3);
  perform set_config('request.jwt.claim.sub', a::text, true); j := public.ttt_move(1);
  perform set_config('request.jwt.claim.sub', b::text, true); j := public.ttt_move(4);
  update public.ttt_games set seen = '{}'::jsonb where partnership_id = pid;
  perform set_config('request.jwt.claim.sub', a::text, true); j := public.ttt_move(8);
  select count(*) into n from public.messages where partnership_id = pid and body = 'game_move|ttt'; out := out || 'pings after A3rd=' || n || '; ';
  perform set_config('request.jwt.claim.sub', b::text, true); j := public.ttt_move(7);
  out := out || 'board=' || (j->>'board') || '; ';
  perform set_config('request.jwt.claim.sub', a::text, true);
  j := public.ttt_state(); out := out || 'A must_slide=' || (j->>'must_slide') || ' my_turn=' || (j->>'my_turn') || '; ';
  begin perform public.ttt_move(2); exception when others then e := sqlerrm; end; out := out || 'A place with 3=' || e || '; ';
  begin perform public.ttt_slide(3, 2); exception when others then e := sqlerrm; end; out := out || 'slide their mark=' || e || '; ';
  begin perform public.ttt_slide(0, 1); exception when others then e := sqlerrm; end; out := out || 'slide onto occupied=' || e || '; ';
  begin perform public.ttt_slide(0, 0); exception when others then e := sqlerrm; end; out := out || 'slide same=' || e || '; ';
  begin perform public.ttt_slide(0, 9); exception when others then e := sqlerrm; end; out := out || 'slide off board=' || e || '; ';
  perform set_config('request.jwt.claim.sub', b::text, true);
  begin perform public.ttt_slide(3, 2); exception when others then e := sqlerrm; end; out := out || 'B out of turn=' || e || '; ';
  perform set_config('request.jwt.claim.sub', a::text, true);
  j := public.ttt_slide(8, 2);
  out := out || 'win status=' || (j->>'status') || ' winner=' || (j->>'winner') || ' line=' || (j->>'line') || ' last=' || (j->>'last_from') || '>' || (j->>'last_to') || ' board=' || (j->>'board') || '; ';
  perform set_config('request.jwt.claim.sub', b::text, true);
  j := public.ttt_again(); out := out || 'again status=' || (j->>'status') || ' board=' || (j->>'board') || '; ';
  raise exception 'ROLLBACK_OK %', out;
end $$;
