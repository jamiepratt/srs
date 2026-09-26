-- Remove the authenticated user and all app data in one transaction.
create function public.delete_account()
returns void language plpgsql security definer set search_path = '' as $$
declare account_id uuid := (select auth.uid());
begin
  if account_id is null then raise exception 'Not signed in'; end if;
  perform 1 from auth.users where id = account_id for update;
  if not found then raise exception 'Account not found'; end if;
  delete from public.cards where user_id = account_id;
  delete from public.decks where user_id = account_id;
  delete from auth.users where id = account_id;
end;
$$;
revoke all on function public.delete_account() from public, anon;
grant execute on function public.delete_account() to authenticated;
