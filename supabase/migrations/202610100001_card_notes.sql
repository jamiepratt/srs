-- Optional card notes are copied as content; schedules remain private.
alter table public.cards add column notes text not null default '';

create or replace function public.copy_shared_deck(source_owner uuid, source_name text,
                                        target_name text, new_schedule jsonb)
returns integer
language plpgsql security definer set search_path = '' as $$
declare
  account_id uuid := (select auth.uid());
  copied integer;
begin
  if account_id is null then raise exception 'Not signed in'; end if;
  if source_owner is null or source_name is null or target_name is null
     or length(btrim(target_name)) not between 1 and 100
     or target_name <> btrim(target_name) then
    raise exception 'Invalid deck';
  end if;
  if new_schedule is null or new_schedule ->> 'fsrs-version' <> '6'
     or not new_schedule ? 'step' then
    raise exception 'Invalid schedule';
  end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(account_id::text, 0));
  perform 1 from public.decks
  where user_id = source_owner and name = source_name and is_shared for share;
  if not found then raise exception 'Shared deck not found'; end if;
  if exists (select 1 from public.decks
             where user_id = account_id and lower(name) = lower(target_name)) then
    raise exception 'Deck name already exists';
  end if;
  insert into public.decks(user_id, name, is_shared)
  values (account_id, target_name, false);
  insert into public.cards(id, user_id, front, back, notes, deck, schedule, reviews, revision)
  select gen_random_uuid(), account_id, c.front, c.back, c.notes, target_name,
         new_schedule, '[]'::jsonb, 0
  from public.cards c
  where c.user_id = source_owner and c.deck = source_name
  order by c.created_at, c.id;
  get diagnostics copied = row_count;
  return copied;
end;
$$;
revoke all on function public.copy_shared_deck(uuid, text, text, jsonb) from public, anon;
grant execute on function public.copy_shared_deck(uuid, text, text, jsonb) to authenticated;

create or replace function public.copy_shared_decks_for_new_user()
returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  source_deck record;
  copy_name text;
  fresh_schedule jsonb;
begin
  fresh_schedule := jsonb_build_object(
    'due', now(), 'fsrs-version', 6, 'step', 0,
    'stability', 0, 'difficulty', 0, 'elapsed-days', 0,
    'scheduled-days', 0, 'reps', 0, 'lapses', 0,
    'state', 'new', 'last-repeat', now());
  for source_deck in
    select user_id, name from public.decks where is_shared
    order by lower(name), user_id
  loop
    copy_name := source_deck.name;
    if exists (select 1 from public.decks
               where user_id = new.id and lower(name) = lower(copy_name)) then
      copy_name := left(copy_name, 89) || ' (' || left(source_deck.user_id::text, 8) || ')';
    end if;
    insert into public.decks(user_id, name, is_shared)
    values (new.id, copy_name, false);
    insert into public.cards(id, user_id, front, back, notes, deck, schedule, reviews, revision)
    select gen_random_uuid(), new.id, c.front, c.back, c.notes, copy_name,
           fresh_schedule, '[]'::jsonb, 0
    from public.cards c
    where c.user_id = source_deck.user_id and c.deck = source_deck.name
    order by c.created_at, c.id;
  end loop;
  return new;
end;
$$;
revoke all on function public.copy_shared_decks_for_new_user() from public, anon, authenticated;
