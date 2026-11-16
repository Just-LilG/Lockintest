-- In-app account deletion (Google Play requires it). Applied as `delete_my_account`.
-- Every table with this person's data references auth.users ON DELETE CASCADE (crew bookkeeping uses SET NULL),
-- so deleting the auth user removes everything of theirs, including their half of the chat.
-- Tested in a rolled-back transaction: a throwaway user was created, the function called as that user, and the
-- user and profile rows were gone afterwards.
create or replace function public.delete_my_account()
returns void
language plpgsql
security definer
set search_path = public, auth
as $$
declare uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'not_signed_in';
  end if;
  delete from auth.users where id = uid;
end;
$$;

revoke all on function public.delete_my_account() from public, anon;
grant execute on function public.delete_my_account() to authenticated;
