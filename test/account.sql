insert into auth.users (id) values
  ('00000000-0000-0000-0000-000000000101'),
  ('00000000-0000-0000-0000-000000000102');
insert into public.decks (user_id, name) values
  ('00000000-0000-0000-0000-000000000101', 'Mine'),
  ('00000000-0000-0000-0000-000000000102', 'Theirs');
insert into public.cards (id, user_id, deck, front, back, schedule) values
  ('00000000-0000-0000-0000-000000000111', '00000000-0000-0000-0000-000000000101', 'Mine', 'q', 'a', '{"fsrs-version":6,"step":0}'),
  ('00000000-0000-0000-0000-000000000112', '00000000-0000-0000-0000-000000000102', 'Theirs', 'q', 'a', '{"fsrs-version":6,"step":0}');

do $$ begin
  perform set_config('request.jwt.claim.sub', '', true);
  begin
    perform public.delete_account();
    raise exception 'Anonymous deletion accepted';
  exception when others then
    if sqlerrm <> 'Not signed in' then raise; end if;
  end;
  perform set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000101', true);
  perform public.delete_account();
  if exists (select 1 from auth.users where id = '00000000-0000-0000-0000-000000000101')
     or exists (select 1 from public.cards where user_id = '00000000-0000-0000-0000-000000000101')
     or exists (select 1 from public.decks where user_id = '00000000-0000-0000-0000-000000000101') then
    raise exception 'Account data remained';
  end if;
  if not exists (select 1 from auth.users where id = '00000000-0000-0000-0000-000000000102')
     or not exists (select 1 from public.cards where user_id = '00000000-0000-0000-0000-000000000102') then
    raise exception 'Other account was changed';
  end if;
end $$;
