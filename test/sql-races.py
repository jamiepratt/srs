"""Exercise real concurrent transactions against the temporary test database."""
import subprocess
import sys
import time

database = sys.argv[1]
user = '00000000-0000-0000-0000-000000000003'

def sql(statement, check=True):
    return subprocess.run(['psql', '-X', '-v', 'ON_ERROR_STOP=1', '-d', database,
                           '-c', statement], capture_output=True, text=True, check=check)

def background(statement):
    return subprocess.Popen(['psql', '-X', '-v', 'ON_ERROR_STOP=1', '-d', database,
                             '-c', statement], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

def wait_for_sleep():
    for _ in range(100):
        if 'PgSleep' in sql("select wait_event from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid()").stdout:
            return
        time.sleep(.02)
    raise AssertionError('Concurrent transaction did not reach barrier')

auth = f"set request.jwt.claim.sub='{user}'; set role authenticated;"
sql(f"insert into auth.users values ('{user}'); insert into decks values ('{user}','A'),('{user}','B');")
first = background(auth + "begin; select delete_deck('A'); select pg_sleep(1); commit;")
wait_for_sleep()
second = sql(auth + "select delete_deck('B');", check=False)
assert first.communicate()[1] == '' and first.returncode == 0
assert second.returncode != 0 and 'Keep at least one deck' in second.stderr, second.stderr
sql(f"insert into decks values ('{user}','Race');")
first = background(auth + f"begin; insert into cards(id,user_id,front,back,deck,schedule) values ('00000000-0000-0000-0000-000000000031','{user}','Q','A','Race','{{\"fsrs-version\":6,\"step\":null}}'); select pg_sleep(1); commit;")
wait_for_sleep()
second = sql(auth + "select delete_deck('Race');", check=False)
assert first.communicate()[1] == '' and first.returncode == 0
assert second.returncode != 0 and ('foreign key' in second.stderr or 'not empty' in second.stderr), second.stderr
assert '1' in sql("select count(*) from cards where deck='Race'").stdout
print('Concurrent final-deck deletions and card-insert/delete race passed')
