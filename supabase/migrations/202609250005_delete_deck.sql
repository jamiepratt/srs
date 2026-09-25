-- Materialize legacy decks before enforcing membership.
insert into public.decks (user_id, name)
select distinct user_id, deck from public.cards
on conflict do nothing;

alter table public.cards add constraint cards_deck_fkey
  foreign key (user_id, deck) references public.decks (user_id, name)
  on update cascade on delete restrict;

-- Imports and old clients can create a named deck by inserting a card.
-- Do this in the same transaction: deletion can never orphan or remove cards.
create function public.ensure_inserted_card_deck()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  insert into public.decks (user_id, name) values (new.user_id, new.deck)
  on conflict do nothing;
  return new;
end;
$$;
revoke all on function public.ensure_inserted_card_deck() from public, anon, authenticated;
create trigger ensure_inserted_card_deck
before insert on public.cards
for each row execute function public.ensure_inserted_card_deck();

create function public.delete_deck(deck_name text)
returns void language plpgsql security definer set search_path = '' as $$
declare account_id uuid := (select auth.uid());
begin
  if account_id is null then raise exception 'Not signed in'; end if;
  -- Serialize deletions for this account, including two different empty decks.
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(account_id::text, 0));
  if not exists (select 1 from public.decks where user_id=account_id and name=deck_name) then
    raise exception 'Deck not found. Reload your decks.';
  end if;
  if exists (select 1 from public.cards where user_id=account_id and deck=deck_name) then
    raise exception 'Deck is not empty. Move all cards to another deck first.';
  end if;
  if (select count(*) from public.decks where user_id=account_id) < 2 then
    raise exception 'Keep at least one deck. Create another deck first.';
  end if;
  delete from public.decks where user_id=account_id and name=deck_name;
end;
$$;
revoke all on function public.delete_deck(text) from public, anon;
grant execute on function public.delete_deck(text) to authenticated;
