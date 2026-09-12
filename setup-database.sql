-- Run this once, as the postgres superuser, before starting the app:
--
--   psql -U postgres -f setup-database.sql
--
-- It creates the database and a dedicated application user. The app never
-- connects as the superuser, which is how it should be.

CREATE USER meetjava WITH PASSWORD 'meetjava';
CREATE DATABASE meetjava OWNER meetjava;

\connect meetjava
GRANT ALL PRIVILEGES ON DATABASE meetjava TO meetjava;
GRANT ALL ON SCHEMA public TO meetjava;
