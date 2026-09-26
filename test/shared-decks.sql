\set ON_ERROR_STOP on
insert into cards(id,user_id,front,back,deck,schedule) values
('00000000-0000-0000-0000-000000000021',
 '00000000-0000-0000-0000-000000000001','Question','Answer','Renamed',
 '{"fsrs-version":6,"step":0,"state":"review"}'::jsonb);
update decks set is_shared=true where user_id='00000000-0000-0000-0000-000000000001'
  and name='Renamed';
insert into auth.users(id) values ('00000000-0000-0000-0000-000000000004');
do $$
begin
  if not exists (select 1 from decks where user_id='00000000-0000-0000-0000-000000000004'
                 and name='Renamed' and not is_shared) then
    raise exception 'New account missing private copy';
  end if;
end;
$$;
set request.jwt.claim.sub='00000000-0000-0000-0000-000000000002';
set role authenticated;
do $$
begin
  if not exists (select 1 from shared_deck_catalog()
                 where owner_id='00000000-0000-0000-0000-000000000001'
                   and deck_name='Renamed') then
    raise exception 'Shared deck not listed';
  end if;
  if exists (select 1 from cards where user_id='00000000-0000-0000-0000-000000000001') then
    raise exception 'Other user cards leaked through RLS';
  end if;
end;
$$;
select copy_shared_deck('00000000-0000-0000-0000-000000000001',
                        'Renamed', 'Clone',
                        '{"fsrs-version":6,"step":0,"state":"new"}'::jsonb);
do $$
begin
  if not exists (select 1 from decks where name='Clone' and not is_shared) then
    raise exception 'Copied deck should stay private';
  end if;
  if not exists (select 1 from cards where deck='Clone' and reviews='[]'::jsonb
                 and schedule->>'state'='new') then
    raise exception 'Cards were not copied with fresh progress';
  end if;
  if exists (select 1 from shared_deck_catalog() where deck_name='Clone') then
    raise exception 'Personal copy republished';
  end if;
end;
$$;
insert into decks(user_id,name) values
  ('00000000-0000-0000-0000-000000000002','Merge target');
select merge_decks('Clone','Merge target');
do $$
begin
  if exists (select 1 from decks where name='Clone') then
    raise exception 'Merge source remained';
  end if;
  if not exists (select 1 from cards where deck='Merge target' and revision=1) then
    raise exception 'Merge did not move cards or bump revision';
  end if;
end;
$$;
reset role;
set role anon;
do $$
begin
  begin
    perform shared_deck_catalog();
    raise exception 'Expected anonymous catalog rejection';
  exception when insufficient_privilege then null;
  end;
end;
$$;
reset role;
