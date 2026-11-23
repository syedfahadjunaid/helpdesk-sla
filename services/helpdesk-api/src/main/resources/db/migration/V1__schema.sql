-- Helpdesk schema. Flyway runs this as the schema owner; the application logs in as
-- ${appRole}, which only gets the privileges granted at the bottom of this file.
--
-- Conventions:
--   * ids are bigint identities; ticket ids start at 1001 and are shown as "HD-1001";
--   * every timestamp is timestamptz (an instant), never a local time;
--   * rules that must never be broken are CHECK constraints, unique indexes or triggers,
--     so they hold even if application code has a bug.

-- ---------------------------------------------------------------------------------------
-- Business calendars: when the SLA clock is allowed to tick
-- ---------------------------------------------------------------------------------------
CREATE TABLE business_calendars (
    id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name      text NOT NULL UNIQUE,
    time_zone text NOT NULL            -- IANA zone id, e.g. Europe/London
);

-- Opening hours as minutes after local midnight: 540 = 09:00, 1440 = end of the day.
-- (Minutes rather than "time" because 24:00 has no java.time.LocalTime equivalent.)
CREATE TABLE business_hours (
    calendar_id   bigint   NOT NULL REFERENCES business_calendars (id) ON DELETE CASCADE,
    day_of_week   smallint NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),   -- ISO 8601: 1 = Monday
    opens_minute  smallint NOT NULL CHECK (opens_minute BETWEEN 0 AND 1439),
    closes_minute smallint NOT NULL CHECK (closes_minute BETWEEN 1 AND 1440),
    PRIMARY KEY (calendar_id, day_of_week, opens_minute),
    CHECK (opens_minute < closes_minute)
);

CREATE TABLE holidays (
    calendar_id  bigint NOT NULL REFERENCES business_calendars (id) ON DELETE CASCADE,
    holiday_date date   NOT NULL,
    name         text   NOT NULL,
    PRIMARY KEY (calendar_id, holiday_date)
);

-- ---------------------------------------------------------------------------------------
-- SLA policies: one per priority
-- ---------------------------------------------------------------------------------------
CREATE TABLE sla_policies (
    priority               text    PRIMARY KEY CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    first_response_minutes integer NOT NULL CHECK (first_response_minutes > 0),
    resolution_minutes     integer NOT NULL CHECK (resolution_minutes > 0),
    warning_percent        integer NOT NULL CHECK (warning_percent BETWEEN 1 AND 99),
    calendar_id            bigint  NOT NULL REFERENCES business_calendars (id),
    updated_at             timestamptz NOT NULL,
    version                bigint  NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------------------------------
-- People
-- ---------------------------------------------------------------------------------------
-- Staff accounts live in Keycloak; this row is created on first sign-in ("just in time").
CREATE TABLE agents (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject      text NOT NULL UNIQUE,     -- Keycloak's user id (the token's "sub" claim)
    username     text NOT NULL,
    email        text,
    display_name text NOT NULL,
    -- Copied from the token's roles at each sign-in, so background jobs (which have no
    -- token) know whom to escalate to. Authorization itself always uses the live token.
    supervisor   boolean NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL
);

-- Customers never sign in; they are known by the address they email from.
CREATE TABLE customers (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email        text NOT NULL UNIQUE CHECK (email = lower(email)),
    display_name text,
    created_at   timestamptz NOT NULL
);

-- ---------------------------------------------------------------------------------------
-- Queues and membership
-- ---------------------------------------------------------------------------------------
CREATE TABLE queues (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    key             text    NOT NULL UNIQUE,
    name            text    NOT NULL,
    inbound_address text UNIQUE CHECK (inbound_address = lower(inbound_address)),
    is_default      boolean NOT NULL DEFAULT false
);
-- At most one default queue (where email to an unknown address ends up).
CREATE UNIQUE INDEX queues_one_default ON queues (is_default) WHERE is_default;

CREATE TABLE queue_members (
    queue_id bigint NOT NULL REFERENCES queues (id) ON DELETE CASCADE,
    agent_id bigint NOT NULL REFERENCES agents (id) ON DELETE CASCADE,
    PRIMARY KEY (queue_id, agent_id)
);

-- ---------------------------------------------------------------------------------------
-- Tickets
-- ---------------------------------------------------------------------------------------
CREATE TABLE tickets (
    id                 bigint GENERATED ALWAYS AS IDENTITY (START WITH 1001) PRIMARY KEY,
    subject            text    NOT NULL,
    status             text    NOT NULL CHECK (status IN ('NEW', 'OPEN', 'WAITING_ON_CUSTOMER', 'RESOLVED', 'CLOSED')),
    priority           text    NOT NULL CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    channel            text    NOT NULL CHECK (channel IN ('EMAIL', 'AGENT')),
    queue_id           bigint  NOT NULL REFERENCES queues (id),
    customer_id        bigint  NOT NULL REFERENCES customers (id),
    assignee_id        bigint REFERENCES agents (id),
    escalation_level   integer NOT NULL DEFAULT 0 CHECK (escalation_level >= 0),
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    first_responded_at timestamptz,
    resolved_at        timestamptz,
    closed_at          timestamptz,
    version            bigint  NOT NULL DEFAULT 0,
    -- "NEW" means exactly "nobody has picked it up yet".
    CONSTRAINT tickets_new_iff_unassigned CHECK ((status = 'NEW') = (assignee_id IS NULL))
);
CREATE INDEX tickets_queue_status ON tickets (queue_id, status);
CREATE INDEX tickets_assignee_status ON tickets (assignee_id, status);
CREATE INDEX tickets_resolved ON tickets (resolved_at) WHERE status = 'RESOLVED';

-- The conversation. Append-only: what was said can't be rewritten (trigger below).
CREATE TABLE ticket_messages (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id          bigint NOT NULL REFERENCES tickets (id),
    author_type        text   NOT NULL CHECK (author_type IN ('CUSTOMER', 'AGENT')),
    author_agent_id    bigint REFERENCES agents (id),
    author_customer_id bigint REFERENCES customers (id),
    visibility         text   NOT NULL CHECK (visibility IN ('PUBLIC', 'INTERNAL')),
    body               text   NOT NULL,
    email_message_id   text,       -- RFC 5322 Message-ID, for messages that came in by email
    created_at         timestamptz NOT NULL,
    CHECK ((author_type = 'AGENT') = (author_agent_id IS NOT NULL)),
    CHECK ((author_type = 'CUSTOMER') = (author_customer_id IS NOT NULL)),
    CHECK (author_type = 'AGENT' OR visibility = 'PUBLIC')     -- customers can't write internal notes
);
CREATE INDEX ticket_messages_ticket ON ticket_messages (ticket_id, id);

-- ---------------------------------------------------------------------------------------
-- SLA timers: the clock state lives here, so it survives restarts and is shared by every
-- instance. There are no in-memory timers: a background worker compares due_at/warn_at
-- with the current time.
-- ---------------------------------------------------------------------------------------
CREATE TABLE sla_timers (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id        bigint  NOT NULL REFERENCES tickets (id),
    metric           text    NOT NULL CHECK (metric IN ('FIRST_RESPONSE', 'RESOLUTION')),
    target_minutes   integer NOT NULL CHECK (target_minutes > 0),
    warning_percent  integer NOT NULL CHECK (warning_percent BETWEEN 1 AND 99),
    calendar_id      bigint  NOT NULL REFERENCES business_calendars (id),
    clock_state      text    NOT NULL CHECK (clock_state IN ('RUNNING', 'PAUSED', 'STOPPED')),
    consumed_seconds bigint  NOT NULL DEFAULT 0 CHECK (consumed_seconds >= 0),  -- business time used before running_since
    running_since    timestamptz,
    due_at           timestamptz,    -- when the target is reached, if the clock keeps running
    warn_at          timestamptz,    -- when the warning threshold is reached (null once warned)
    warned_at        timestamptz,
    breached_at      timestamptz,
    met_at           timestamptz,
    UNIQUE (ticket_id, metric),
    CONSTRAINT sla_timers_running_has_start CHECK ((clock_state = 'RUNNING') = (running_since IS NOT NULL)),
    CONSTRAINT sla_timers_running_has_due CHECK ((clock_state = 'RUNNING') = (due_at IS NOT NULL))
);
-- What the worker scans: small partial indexes over running clocks only.
CREATE INDEX sla_timers_breach_scan ON sla_timers (due_at) WHERE clock_state = 'RUNNING' AND breached_at IS NULL;
CREATE INDEX sla_timers_warning_scan ON sla_timers (warn_at) WHERE clock_state = 'RUNNING' AND warned_at IS NULL;

-- ---------------------------------------------------------------------------------------
-- Email intake
-- ---------------------------------------------------------------------------------------
-- One row per inbound email, keyed by its Message-ID: the same email fetched twice
-- (a retry, or two instances polling at once) is recognised and skipped.
CREATE TABLE inbound_emails (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id    text NOT NULL UNIQUE,
    mailbox_ref   text,              -- the mail server's own id for it (Mailpit's database id)
    from_address  text NOT NULL,
    subject       text,
    received_at   timestamptz NOT NULL,
    outcome       text NOT NULL CHECK (outcome IN ('PROCESSING', 'CREATED_TICKET', 'APPENDED', 'IGNORED')),
    outcome_reason text,
    ticket_id     bigint REFERENCES tickets (id)
);

-- Every Message-ID known to belong to a ticket (inbound and outbound), so a reply's
-- In-Reply-To / References headers lead back to the right ticket.
CREATE TABLE email_threads (
    message_id text   PRIMARY KEY,
    ticket_id  bigint NOT NULL REFERENCES tickets (id)
);

-- ---------------------------------------------------------------------------------------
-- Transactional outbox: domain events are written in the same transaction as the change
-- they describe, then a relay publishes them to RabbitMQ (at least once).
-- ---------------------------------------------------------------------------------------
CREATE TABLE outbox_events (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id        uuid    NOT NULL UNIQUE,
    event_type      text    NOT NULL,          -- also the RabbitMQ routing key
    ticket_id       bigint,
    payload         jsonb   NOT NULL,
    occurred_at     timestamptz NOT NULL,
    published_at    timestamptz,
    attempts        integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    last_error      text
);
CREATE INDEX outbox_events_pending ON outbox_events (next_attempt_at, id) WHERE published_at IS NULL;

-- Consumers record each event id they've handled, so a redelivered message is a no-op.
CREATE TABLE processed_messages (
    consumer     text NOT NULL,
    event_id     uuid NOT NULL,
    processed_at timestamptz NOT NULL,
    PRIMARY KEY (consumer, event_id)
);

-- ---------------------------------------------------------------------------------------
-- In-app notifications
-- ---------------------------------------------------------------------------------------
CREATE TABLE notifications (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    agent_id        bigint NOT NULL REFERENCES agents (id),
    ticket_id       bigint REFERENCES tickets (id),
    kind            text   NOT NULL,
    message         text   NOT NULL,
    source_event_id uuid   NOT NULL,
    created_at      timestamptz NOT NULL,
    read_at         timestamptz,
    UNIQUE (agent_id, source_event_id)     -- one notification per person per event, however often it's delivered
);
CREATE INDEX notifications_unread ON notifications (agent_id, id) WHERE read_at IS NULL;

-- ---------------------------------------------------------------------------------------
-- Audit history: append-only
-- ---------------------------------------------------------------------------------------
CREATE TABLE audit_events (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id   bigint REFERENCES tickets (id),
    actor_type  text  NOT NULL CHECK (actor_type IN ('AGENT', 'CUSTOMER', 'SYSTEM')),
    actor_id    bigint,
    actor_name  text  NOT NULL,
    action      text  NOT NULL,
    details     jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL
);
CREATE INDEX audit_events_ticket ON audit_events (ticket_id, id);
-- Belt and braces for the escalation worker: the database itself refuses a second
-- warning or breach record for the same ticket and SLA metric.
CREATE UNIQUE INDEX audit_events_sla_once ON audit_events (ticket_id, action, (details ->> 'metric'))
    WHERE action IN ('SLA_WARNING', 'SLA_BREACHED');

-- Append-only, enforced by the database: even a buggy or compromised application can't
-- quietly rewrite history.
CREATE FUNCTION reject_history_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only (% is not allowed)', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TRIGGER audit_events_append_only
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_history_change();
CREATE TRIGGER audit_events_no_truncate
    BEFORE TRUNCATE ON audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_history_change();

CREATE TRIGGER ticket_messages_append_only
    BEFORE UPDATE OR DELETE ON ticket_messages
    FOR EACH ROW EXECUTE FUNCTION reject_history_change();
CREATE TRIGGER ticket_messages_no_truncate
    BEFORE TRUNCATE ON ticket_messages
    FOR EACH STATEMENT EXECUTE FUNCTION reject_history_change();

-- ---------------------------------------------------------------------------------------
-- Spring Session (HTTP sessions shared by every instance). Copied from
-- org/springframework/session/jdbc/schema-postgresql.sql in spring-session-jdbc.
-- ---------------------------------------------------------------------------------------
CREATE TABLE spring_session (
    primary_id            char(36) NOT NULL,
    session_id            char(36) NOT NULL,
    creation_time         bigint   NOT NULL,
    last_access_time      bigint   NOT NULL,
    max_inactive_interval integer  NOT NULL,
    expiry_time           bigint   NOT NULL,
    principal_name        varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id char(36)     NOT NULL,
    attribute_name     varchar(200) NOT NULL,
    attribute_bytes    bytea        NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------------------
-- Least privilege for the application login
-- ---------------------------------------------------------------------------------------
GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO ${appRole};
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ${flyway:defaultSchema} TO ${appRole};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA ${flyway:defaultSchema} TO ${appRole};
-- History tables: the app may add rows, never change or remove them.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_events, ticket_messages FROM ${appRole};
-- Reference data is maintained by migrations (and SLA targets through the admin API).
REVOKE INSERT, UPDATE, DELETE ON business_calendars, business_hours, holidays FROM ${appRole};
