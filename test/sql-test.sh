#!/usr/bin/env bash
set -euo pipefail
# Requires local PostgreSQL and a role allowed to create databases.
db="srs_decks_test_$$"
createdb "$db"
created_roles=()
cleanup() {
  dropdb "$db"
  for role in ${created_roles[@]+"${created_roles[@]}"}; do dropuser "$role"; done
}
trap cleanup EXIT
for role in authenticated anon; do
  if [[ $(psql -X -At -d "$db" -c "select count(*) from pg_roles where rolname='$role'") == 0 ]]; then
    createuser "$role"
    created_roles+=("$role")
  fi
done
psql -X -v ON_ERROR_STOP=1 -d "$db" <<'SQL'
CREATE SCHEMA auth;
CREATE TABLE auth.users(id uuid PRIMARY KEY);
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS 'SELECT nullif(current_setting(''request.jwt.claim.sub'',true),'''')::uuid';
GRANT USAGE ON SCHEMA auth TO authenticated, anon;
SQL
for migration in supabase/migrations/*.sql; do psql -X -v ON_ERROR_STOP=1 -d "$db" -f "$migration" >/dev/null; done
psql -X -v ON_ERROR_STOP=1 -d "$db" -f test/decks.sql
psql -X -v ON_ERROR_STOP=1 -d "$db" -f test/shared-decks.sql
python3 test/sql-races.py "$db"
