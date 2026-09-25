create function public.rename_deck(old_name text, new_name text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  account_id uuid := (select auth.uid());
begin
  if account_id is null then
    raise exception 'Not signed in';
  end if;

  if old_name is null or new_name is null
     or length(btrim(new_name)) not between 1 and 100
     or new_name <> btrim(new_name) then
    raise exception 'Invalid deck name';
  end if;

  if old_name = new_name then
    return;
  end if;

  if not exists (select 1 from public.decks
                 where user_id = account_id and name = old_name)
     and not exists (select 1 from public.cards
                     where user_id = account_id and deck = old_name)
     and not (old_name = 'Default'
              and not exists (select 1 from public.decks
                              where user_id = account_id)
              and not exists (select 1 from public.cards
                              where user_id = account_id)) then
    raise exception 'Deck not found';
  end if;

  if exists (select 1 from public.decks
             where user_id = account_id and lower(name) = lower(new_name))
     or exists (select 1 from public.cards
                where user_id = account_id and lower(deck) = lower(new_name)) then
    raise exception 'Deck name already exists';
  end if;

  insert into public.decks (user_id, name) values (account_id, new_name);
  update public.cards
  set deck = new_name, revision = revision + 1
  where user_id = account_id and deck = old_name;
  delete from public.decks where user_id = account_id and name = old_name;
end;
$$;

revoke all on function public.rename_deck(text, text) from public, anon;
grant execute on function public.rename_deck(text, text) to authenticated;
