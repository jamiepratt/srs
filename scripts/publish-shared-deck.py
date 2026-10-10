#!/usr/bin/env python3
"""Publish a reviewed content-only backup as a shared template without resetting progress.

Dry run by default. --apply requires deployed audio to match local assets first.
"""
import argparse
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import subprocess
import tempfile
from urllib.parse import urlparse
from urllib.request import urlopen
import uuid

ROOT = Path(__file__).resolve().parents[1]

class AudioPaths(HTMLParser):
    def __init__(self):
        super().__init__()
        self.urls = set()
    def handle_starttag(self, tag, attrs):
        if tag == 'audio':
            self.urls.add(dict(attrs)['src'])

def literal(value):
    return "'" + value.replace("'", "''") + "'"

def make_sql(backup, owner):
    owner = str(uuid.UUID(owner))
    deck = backup['deck_name']
    cards = backup['cards']
    if backup.get('version') != 2 or backup.get('scheduling_data') is not False or not cards:
        raise ValueError('Use a nonempty version 2 content-only backup')
    ids = [str(uuid.UUID(c['id'])) for c in cards]
    if len(ids) != len(set(ids)) or any(c['deck'] != deck for c in cards):
        raise ValueError('Expected unique card IDs and one deck')
    if any(not isinstance(c.get(k), str) for c in cards for k in ['front', 'back', 'notes']):
        raise ValueError('Front, back and notes must be strings')
    idlist = ','.join(literal(i) for i in ids)
    fresh = "jsonb_build_object('due',now(),'last-repeat',now(),'fsrs-version',6,'step',0,'stability',0,'difficulty',0,'elapsed-days',0,'scheduled-days',0,'reps',0,'lapses',0,'state','new')"
    sql = f"""begin;
select pg_advisory_xact_lock(hashtextextended({literal(owner)},0));
do $guard$ begin
  if not exists(select 1 from auth.users where id={literal(owner)}) then
    raise exception 'Template owner not found';
  end if;
  if exists(select 1 from public.cards where id in ({idlist}) and user_id<>{literal(owner)}) then
    raise exception 'Card IDs already owned by another account';
  end if;
  if exists(select 1 from public.cards where user_id={literal(owner)} and deck={literal(deck)} and id not in ({idlist})) then
    raise exception 'Deck contains other cards; review manually';
  end if;
end $guard$;
insert into public.decks(user_id,name,is_shared) values({literal(owner)},{literal(deck)},true)
on conflict(user_id,name) do update set is_shared=true;
"""
    for i,c in enumerate(cards):
        sql += f"""insert into public.cards(id,user_id,front,back,notes,deck,schedule,reviews,revision,created_at)
values({literal(c['id'])},{literal(owner)},{literal(c['front'])},{literal(c['back'])},{literal(c['notes'])},{literal(deck)},{fresh},'[]'::jsonb,0,now()+interval '{i} milliseconds')
on conflict(id) do update set front=excluded.front,back=excluded.back,notes=excluded.notes,deck=excluded.deck,revision=cards.revision+1
where (cards.front,cards.back,cards.notes,cards.deck) is distinct from (excluded.front,excluded.back,excluded.notes,excluded.deck);
"""
    sql += 'commit;\n'
    return sql

def verify_audio(backup):
    parser = AudioPaths()
    for card in backup['cards']:
        parser.feed(card['front'])
    for url in sorted(parser.urls):
        parsed = urlparse(url)
        if parsed.scheme != 'https' or parsed.netloc != 'srs.submergedstructure.com' or not parsed.path.startswith('/audio/'):
            raise ValueError(f'Unexpected audio host/path: {url}')
        local = ROOT / 'docs' / parsed.path.lstrip('/')
        with urlopen(url, timeout=30) as response:
            deployed = response.read()
        if hashlib.sha256(deployed).digest() != hashlib.sha256(local.read_bytes()).digest():
            raise ValueError(f'Deployed audio does not match: {url}')
    print(f'Verified {len(parser.urls)} deployed audio clips')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('backup',type=Path)
    parser.add_argument('--owner',required=True)
    parser.add_argument('--apply',action='store_true')
    parser.add_argument('--sql-output',type=Path)
    args=parser.parse_args()
    backup=json.loads(args.backup.read_text())
    sql=make_sql(backup,args.owner)
    if args.sql_output:
        args.sql_output.write_text(sql)
    if not args.apply:
        print(f'Ready: {len(backup["cards"])} cards in {backup["deck_name"]}. No database changes.')
        return
    verify_audio(backup)
    with tempfile.NamedTemporaryFile(mode='w',suffix='.sql') as f:
        f.write(sql); f.flush()
        subprocess.run(['supabase','db','query','--linked','--file',f.name],check=True,cwd=ROOT)
    print('Shared deck published. Existing schedules and reviews retained.')

if __name__=='__main__':
    main()
