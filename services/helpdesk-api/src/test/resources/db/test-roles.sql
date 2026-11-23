-- Test database roles, like infra/postgres/init/01-init.sh does in Docker Compose:
-- Flyway runs as the container superuser (the schema owner), the app as helpdesk_app.
CREATE ROLE helpdesk_app LOGIN PASSWORD 'helpdesk-app-test';
ALTER ROLE helpdesk_app SET search_path = helpdesk;
