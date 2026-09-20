-- =====================================================================
-- One-time local database setup. Idempotent: safe to run again.
--
-- Creates the application login (no superuser rights) and two databases it
-- owns: the main one and a test one. The app then runs its own migrations
-- as that login, which is how production works too.
--
-- Run as a Postgres superuser (usually "postgres"), from the repo root:
--
--   psql -h localhost -U postgres -d postgres \
--        -v app_user=engage_app -v app_password='your-password' \
--        -v app_db=engage -v test_db=engage_test \
--        -f scripts/db-setup.sql
--
-- Use the same values you put in config/local.env
-- (DB_USER, DB_PASSWORD, DB_NAME, TEST_DB_NAME).
--
-- Windows (PowerShell): put the command on one line and drop the backslashes.
-- =====================================================================

\set ON_ERROR_STOP 1

\if :{?app_user}
\else
  \echo 'Missing -v app_user=...  (see usage at the top of this file)'
  \quit
\endif
\if :{?app_password}
\else
  \echo 'Missing -v app_password=...'
  \quit
\endif
\if :{?app_db}
\else
  \set app_db engage
\endif
\if :{?test_db}
\else
  \set test_db engage_test
\endif

-- Role: create if missing, otherwise just reset the password to the one given.
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE', :'app_user', :'app_password')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user') \gexec

SELECT format('ALTER ROLE %I WITH LOGIN PASSWORD %L', :'app_user', :'app_password')
 WHERE EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user') \gexec

-- Databases, owned by the app role so migrations run without superuser.
SELECT format('CREATE DATABASE %I OWNER %I ENCODING %L TEMPLATE template0', :'app_db', :'app_user', 'UTF8')
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'app_db') \gexec

SELECT format('CREATE DATABASE %I OWNER %I ENCODING %L TEMPLATE template0', :'test_db', :'app_user', 'UTF8')
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'test_db') \gexec

-- Business timezone as the database default, so ad-hoc psql queries show IST.
-- The application still stores UTC (timestamptz) and converts explicitly.
SELECT format('ALTER DATABASE %I SET timezone TO %L', :'app_db', 'Asia/Kolkata') \gexec

\echo
\echo 'Done. Role and databases are ready.'
\echo 'Next: start the app (it runs migrations), or check with:'
\echo '  psql -h localhost -U <DB_USER> -d <DB_NAME> -c "select version();"'
