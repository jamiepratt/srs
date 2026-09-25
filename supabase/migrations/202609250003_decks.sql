alter table public.cards
  add column deck text not null default 'Default'
  check (length(trim(deck)) between 1 and 100);

create index cards_user_deck_idx on public.cards (user_id, deck);

create table public.decks (
  user_id uuid not null references auth.users (id) on delete cascade,
  name text not null check (length(trim(name)) between 1 and 100),
  primary key (user_id, name)
);

insert into public.decks (user_id, name)
select distinct user_id, 'Default' from public.cards;

alter table public.decks enable row level security;

revoke all on table public.decks from anon, authenticated;
grant select, insert on table public.decks to authenticated;

create policy "Users can read their decks"
on public.decks for select to authenticated
using ((select auth.uid()) = user_id);

create policy "Users can create their decks"
on public.decks for insert to authenticated
with check ((select auth.uid()) = user_id);
