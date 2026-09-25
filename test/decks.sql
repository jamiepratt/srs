\set ON_ERROR_STOP on
insert into auth.users(id) values ('00000000-0000-0000-0000-000000000001'),('00000000-0000-0000-0000-000000000002');
insert into decks values ('00000000-0000-0000-0000-000000000001','Keep'),('00000000-0000-0000-0000-000000000001','Unused'),('00000000-0000-0000-0000-000000000002','Unused');
set request.jwt.claim.sub='00000000-0000-0000-0000-000000000001';
set role authenticated;
select public.delete_deck('Unused');
reset role;
do $$
begin
 if exists(select 1 from decks where user_id='00000000-0000-0000-0000-000000000001' and name='Unused') then raise exception 'Deck remained'; end if;
 if not exists(select 1 from decks where user_id='00000000-0000-0000-0000-000000000002' and name='Unused') then raise exception 'Other user affected'; end if;
end;
$$;
set role authenticated;
do $$
begin
 begin
  perform public.delete_deck('Keep');
  raise exception 'Expected last-deck rejection';
 exception when raise_exception then
  if SQLERRM not like '%Keep at least one deck%' then
   raise;
  end if;
 end;
end;
$$;
-- Old clients and imports may create named decks by inserting cards.
insert into cards(id,user_id,front,back,deck,schedule) values ('00000000-0000-0000-0000-000000000011','00000000-0000-0000-0000-000000000001','Q','A','Imported','{"fsrs-version":6,"step":null}');
do $$
begin
 begin
  perform public.delete_deck('Imported');
  raise exception 'Expected nonempty rejection';
 exception when raise_exception then
  if SQLERRM not like '%Deck is not empty%' then
   raise;
  end if;
 end;
 begin update cards set deck='Unused';
  raise exception 'Expected stale move rejection';
 exception when foreign_key_violation then
  null;
 end;
end;
$$;
select public.rename_deck('Imported','Renamed');
do $$
begin if not exists(select 1 from cards where deck='Renamed') then raise exception 'Rename broken'; end if; end;
$$;
reset role;
-- Privilege and trigger isolation checks.
set role authenticated;
do $$
begin
 begin delete from decks;
  raise exception 'Expected direct delete rejection';
 exception when insufficient_privilege then
  null;
 end;
 begin
  insert into cards(id,user_id,front,back,deck,schedule)
  values ('00000000-0000-0000-0000-000000000012','00000000-0000-0000-0000-000000000002','Q','A','Malicious','{"fsrs-version":6,"step":null}');
  raise exception 'Expected other-user insert rejection';
 exception when insufficient_privilege then
  null;
 end;
end;
$$;
reset role;
do $$
begin if exists(select 1 from decks where name='Malicious') then raise exception 'Trigger leaked other-user deck'; end if; end;
$$;
set role anon;
do $$
begin
 begin
  perform delete_deck('Keep');
  raise exception 'Expected anonymous rejection';
 exception when insufficient_privilege then
  null;
 end;
end;
$$;
reset role;
set request.jwt.claim.sub='';
do $$
begin
 begin
  perform delete_deck('Keep');
  raise exception 'Expected no-user rejection';
 exception when raise_exception then
  if SQLERRM <> 'Not signed in' then
   raise;
  end if;
 end;
end;
$$;
