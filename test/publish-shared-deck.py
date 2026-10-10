"""Exercise template publishing against the disposable SQL test database."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

sys.dont_write_bytecode = True

spec = importlib.util.spec_from_file_location('publisher', 'scripts/publish-shared-deck.py')
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)
backup = json.loads(Path('docs/decks/geeks-english.json').read_text())
owner = '00000000-0000-0000-0000-000000000099'

def query(sql):
    return subprocess.run(['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-d', sys.argv[1]],
                          input=sql, text=True, capture_output=True, check=True).stdout.strip()

query(f"insert into auth.users(id) values('{owner}');")
query(publisher.make_sql(backup, owner))
first = backup['cards'][0]['id']
query(f"update cards set schedule=jsonb_set(schedule,'{{reps}}','7'), reviews='[{{\"rating\":\"good\"}}]' where id='{first}';")
query(publisher.make_sql(backup, owner))
assert query(f"select count(*) from cards where user_id='{owner}' and deck={publisher.literal(backup['deck_name'])};") == '28'
assert query(f"select revision from cards where id='{first}';") == '0', 'Unchanged content should not bump revisions'
backup['cards'][0]['notes'] = '<p>Revised translation note</p>'
query(publisher.make_sql(backup, owner))
assert query(f"select revision || ':' || (schedule->>'reps') || ':' || jsonb_array_length(reviews) from cards where id='{first}';") == '1:7:1'
assert query(f"select notes from cards where id='{first}';") == '<p>Revised translation note</p>'
assert query(f"select is_shared from decks where user_id='{owner}' and name={publisher.literal(backup['deck_name'])};") == 't'
print('Template publish is idempotent; content updates retain progress and history.')
