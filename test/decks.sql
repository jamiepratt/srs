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
-- Card content updates and deletion use the same optimistic revision guard.
set request.jwt.claim.sub='00000000-0000-0000-0000-000000000001';
set role authenticated;
do $$
declare
 before_card cards%rowtype;
 after_card cards%rowtype;
 affected integer;
begin
 select * into before_card from cards where id='00000000-0000-0000-0000-000000000011';
 update cards set front='Edited front',back='Edited back',revision=revision+1
 where id=before_card.id and revision=before_card.revision;
 get diagnostics affected = row_count;
 if affected <> 1 then raise exception 'Guarded edit failed'; end if;
 select * into after_card from cards where id=before_card.id;
 if after_card.schedule is distinct from before_card.schedule or after_card.reviews is distinct from before_card.reviews then raise exception 'Edit lost schedule/history'; end if;
 update cards set front='Stale edit' where id=before_card.id and revision=before_card.revision;
 get diagnostics affected = row_count;
 if affected <> 0 then raise exception 'Stale edit accepted'; end if;
 delete from cards where id=before_card.id and revision=before_card.revision;
 get diagnostics affected = row_count;
 if affected <> 0 then raise exception 'Stale delete accepted'; end if;
 delete from cards where id=after_card.id and revision=after_card.revision;
 get diagnostics affected = row_count;
 if affected <> 1 then raise exception 'Guarded delete failed'; end if;
end;
$$;
reset role;
