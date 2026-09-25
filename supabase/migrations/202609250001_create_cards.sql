create table public.cards (
  id uuid primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  front text not null check (length(trim(front)) > 0),
  back text not null check (length(trim(back)) > 0),
  schedule jsonb not null check (jsonb_typeof(schedule) = 'object'),
  reviews jsonb not null default '[]'::jsonb
    check (jsonb_typeof(reviews) = 'array'),
  revision integer not null default 0 check (revision >= 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index cards_user_id_idx on public.cards (user_id);

create function public.set_card_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

create trigger set_card_updated_at
before update on public.cards
for each row execute function public.set_card_updated_at();

alter table public.cards enable row level security;

revoke all on table public.cards from anon, authenticated;
grant select, insert, update, delete on table public.cards to authenticated;

create policy "Users can read their cards"
on public.cards for select to authenticated
using ((select auth.uid()) = user_id);

create policy "Users can create their cards"
on public.cards for insert to authenticated
with check ((select auth.uid()) = user_id);

create policy "Users can update their cards"
on public.cards for update to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

create policy "Users can delete their cards"
on public.cards for delete to authenticated
using ((select auth.uid()) = user_id);
