# AGENTS.md: guide for coding agents working on this repository

A helpdesk and ticketing system with SLA tracking, built as an enterprise-style reference
project. The code doubles as learning material (the owner is learning Java), so keep the
explanatory comments and idiomatic, modern Java (records, sealed interfaces, exhaustive
switches). The README is the human-facing overview; this file is the operating manual. When
they disagree, check the code and fix the doc.

Sections: [Status](#status-whats-built-and-whats-left) · [Map](#map) ·
[Run it on a new machine](#run-it-on-a-new-machine) · [Run and verify](#run-and-verify) ·
[Request flow](#request-flow) · [Invariants](#invariants-do-not-break-these) ·
[Conventions](#conventions) · [Things that already bit us](#things-that-already-bit-us) ·
[Definition of done](#definition-of-done) · [Learning guide](#learning-guide)

## Status: what's built and what's left

**Done** (all covered by tests; the demo exercises the whole chain):

1. **Ticket core**: explicit state machine, queues, assignment, "next ticket" claim, priority changes, permissions, auto-close 72 h after resolution.
2. **SLA engine**: per-priority policies (first response + resolution), business-hours calendars with bank holidays and daylight saving, pause while waiting on the customer, deadlines stored in the DB, retargeting on priority change.
3. **Escalation worker**: warning and breach, safe on several instances (`FOR UPDATE SKIP LOCKED`, re-check after lock, unique index as a backstop).
4. **Events**: transactional outbox → RabbitMQ topic exchange with publisher confirms; idempotent consumers, retries and dead-letter queues.
5. **Live updates**: WebSocket/STOMP through RabbitMQ's STOMP relay (works across both instances), with subscription rules and an Origin check.
6. **Email to ticket**: Mailpit intake with Message-ID dedup, reply threading, loop protection, quote stripping, follow-up tickets for closed ones. Outbound: acknowledgements, agent replies and breach alerts, with threadable Message-IDs.
7. **Notifications and dashboards**: in-app notification bell and supervisor breach emails; SLA dashboard (load, 7-day compliance, breaches per day, due next); admin editor for policies and queue membership.
8. **Auth**: Keycloak sign-in with the API as its own BFF (sessions in Postgres, CSRF, HttpOnly cookie); bearer tokens for scripts.
9. **Data safety**: least-privilege DB logins, append-only audit and conversation history (trigger + withheld grants), Flyway migrations.
10. **Ops and tooling**: one-command Compose stack with two API instances behind nginx; scripts for secrets, seed, demo, smoke test, live watcher and test emails; CI workflow and Dependabot. The CI workflow is written but has never run.
11. **Tests**: 96 Java tests (unit + Testcontainers: Postgres, RabbitMQ with STOMP, Mailpit, including concurrency tests); 14 web tests.

**Not verified yet**:
- The CI workflow has never run (no remote repository).
- Sign-in has been checked only by `smoke-test.ps1`, which drives Keycloak's real login form; nobody has clicked through it in a real browser.

**What's left, in suggested order.** It matches the README's "Not done yet". Each item says where it goes and how to know it's done.

1. **Run CI on a real remote** (needs the owner: creating the repo and pushing are theirs to do).
   - Push to GitHub; the `api`, `web` and `images` jobs in `.github/workflows/ci.yml` should pass.
   - Likely snags:
     - Trivy failing on a new CVE in a base image: bump the image tag, don't lower `severity`.
     - Testcontainers on `ubuntu-latest`: it should just work, since the runner has Docker.
2. **Housekeeping job** (a small, self-contained first task).
   - What: a `@Scheduled` job in a new package `com.helpdesk.housekeeping`. It deletes `outbox_events` rows published more than N days ago and `processed_messages` rows older than M days.
   - How it runs:
     - batched: `DELETE ... WHERE id IN (SELECT id ... LIMIT :batch FOR UPDATE SKIP LOCKED)`;
     - through `TransactionTemplate`, like `SlaEscalationWorker`.
   - Retention values go in `HelpdeskProperties` and `application.yml`.
   - Keep `processed_messages` longer than a message can sit in a DLQ before someone replays it. Otherwise a replayed event runs twice.
   - Add a partial index on `outbox_events (published_at)` in a new `V3__...sql` if the plan needs it.
   - Test: `IntegrationTest` with `MutableClock`.
   - Spring Session already deletes expired sessions (`spring.session.jdbc.cleanup-cron`, every minute), so sessions need nothing.
3. **Attachments.** Today `EmailParser` skips non-text parts.
   - Schema: table `ticket_attachments` (ticket FK, message FK, file name, content type, size, `bytea` or a volume path) in a new migration, with grants for `${appRole}`.
   - Limits: enforce `helpdesk.mail.inbound.max-size` and an allowlist of content types.
   - Download: `GET /api/tickets/{id}/attachments/{aid}` with `Content-Disposition: attachment` and `X-Content-Type-Options: nosniff`. Never serve inline.
   - Upload: a multipart endpoint for agents.
   - Tests: an `EmailIntakeTests` case with a multipart email.
4. **Real inbound mail (IMAP or a provider webhook).**
   - Extract an interface from `MailpitMailbox` (list unprocessed, fetch raw, mark processed) and pick the implementation with a property.
   - IMAP: Jakarta Mail is already on the classpath through `spring-boot-starter-mail`. Use UIDs plus a keyword flag instead of Mailpit tags.
   - A webhook needs its own `SecurityFilterChain` (an HMAC signature, not a session) and must stay idempotent through the existing Message-ID claim.
5. **Sender verification.**
   - Parse the receiving server's `Authentication-Results` header (RFC 8601). Trust it only when its `authserv-id` is our own MTA.
   - Unverified senders may still create tickets (flag them), but never thread onto an existing ticket.
   - This depends on item 4, because Mailpit doesn't add the header.
6. **Calendars and holidays editable at runtime.**
   - Grants: a new migration grants `helpdesk_app` write access to `business_hours` and `holidays`. Today it is withheld on purpose.
   - API: admin endpoints for both tables.
   - Cache: invalidate `Calendars` on both instances, either with an event on `helpdesk.events` or with a version column checked before use.
   - Running clocks: re-schedule every running clock on that calendar (consumed time stays; `due_at`/`warn_at` are recomputed).
   - Tests: DST and holiday edge cases in `BusinessCalendarTests`.
7. **Precomputed dashboard numbers**, only once the live SQL in `SlaDashboardController` measurably slows down.
   - Option A: a consumer of `ticket.*`/`sla.*` events maintains daily aggregate tables, idempotent through `processed_messages`.
   - Option B: a materialized view refreshed on a schedule.

## Map

| Path | What | Stack |
|---|---|---|
| `services/helpdesk-api/` | The whole backend: REST API, its own BFF (OIDC sign-in, sessions), WebSocket/STOMP endpoint, SLA worker, outbox relay, RabbitMQ consumers, mailbox poller | Java 25, Spring Boot 4.2, Spring Security 7, Hibernate 7, Flyway, Spring AMQP 4, Spring Session JDBC, Maven wrapper |
| `services/helpdesk-api/src/main/resources/db/migration/` | Schema (`V1`) and reference data (`V2`: calendars, holidays, SLA policies, queues) | Postgres 18 |
| `web/` | SPA: tickets, ticket detail, new ticket, SLA dashboard, SLA policies, notifications, live updates | React 19, TypeScript 6, Vite 8, Tailwind 4, TanStack Query, React Router 8, @stomp/stompjs |
| `infra/keycloak/helpdesk-realm.json` | Realm `helpdesk`: roles, clients (`helpdesk-web` confidential, `helpdesk-cli` dev-only), demo users (`${DEMO_USER_PASSWORD}` placeholder) | Keycloak 26.8 |
| `infra/postgres/init/01-init.sh` | Logins: `helpdesk_owner` (Flyway), `helpdesk_app` (runtime), `keycloak` | |
| `infra/rabbitmq/` | Enabled plugins (management, prometheus, **stomp**) and STOMP listener config | RabbitMQ 4.3 |
| `docker-compose.yml` | Everything: postgres, rabbitmq, mailpit, keycloak, **two** API instances, web (nginx) | |
| `scripts/` | `init-env.ps1` (random secrets into `.env`), `lib.ps1` (helpers), `seed-demo.ps1`, `demo.ps1`, `smoke-test.ps1`, `send-email.ps1`, `watch-live.ps1` | PowerShell 5.1 |
| `.github/` | CI (API verify, web lint/test/build, image build + Trivy) and Dependabot | |

Java packages (`com.helpdesk.*`): `ticket` (aggregate, state machine, permissions, service,
API, auto-close), `sla` (calendar maths, timers, policies, worker, dashboard), `events`
(sealed `TicketEvent`, outbox, relay), `messaging` (topology, idempotency, message reading),
`live` (STOMP security, live-update consumer), `notification`, `mail` (outbound, threads) and
`mail.inbound` (parser, intake, Mailpit mailbox, poller), `security`, `queue`, `agent`,
`customer`, `audit`, `config`, `common`.

## Run it on a new machine

Everything runs in Docker: the images build the Java and web code from source, and the
tests run in a throwaway JDK container. A copy without `.env`, `node_modules`, `target`,
`dist` and caches is complete: nothing needed at runtime lives in an ignored folder. This
was checked by running every step below from such a copy, in a path containing a space.

**Prerequisites**

| What | Version | Needed for |
|---|---|---|
| Windows 10/11 | | The scripts are Windows PowerShell. The Docker parts aren't Windows-specific, but they have only been run on Windows |
| Docker Desktop (WSL 2 backend) | Tested with Engine 29.9, Compose v5.6 | Everything. Give it **at least 4 CPUs and 6 GB of memory** (Settings → Resources). The stack was measured at about 2.2 GB; the Java test run adds roughly 1.5 GB |
| Windows PowerShell | 5.1 (built into Windows) | `scripts\*.ps1` |
| Internet access | | First run only: pulls the images and downloads the Maven and npm dependencies (Maven itself comes through the wrapper) |
| Node.js | 24 LTS (tested 24.16), **optional** | Only to run the web tests or the Vite dev server on the host; a Docker alternative is below |
| JDK | 25, **optional** | Never needed: the build and tests run in Docker. Only for an IDE |

Git is not needed. The published ports must be free (see Ports below).

**Steps, from a fresh copy** (PowerShell, in the project folder):

```powershell
# 0. Only if the folder came from a download or an email attachment: clear Windows' "downloaded" mark.
Get-ChildItem -Recurse | Unblock-File
# Scripts are blocked by default on Windows client editions; allow them for this window only:
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass

# 1. Check that the ports are free (no output = free)
Get-NetTCPConnection -State Listen -LocalPort 5580,8580,8581,8680,45432,45672,45673,41025,48025 -ErrorAction SilentlyContinue

# 2. Secrets: writes .env with a random value for every variable in .env.example (never printed)
.\scripts\init-env.ps1

# 3. Build and start everything; --wait returns once all services are healthy.
#    The first time downloads all images and dependencies: 26 min was measured on a busy
#    4-CPU machine with no build cache; much less on an idle one. Later starts reuse the images.
docker compose up -d --build --wait

# 4. The end-to-end demo (~4 min), then put the real urgent SLA back
.\scripts\demo.ps1
.\scripts\seed-demo.ps1 -RestoreSla

# 5. Browser-path checks: sign-in through Keycloak's form, session, CSRF, WebSocket, sign-out
.\scripts\smoke-test.ps1
```

You can copy `.env.example` to `.env` by hand instead of step 2. Then fill in **every**
value with a random string: compose stops with "run scripts/init-env.ps1 to create .env"
while any value is empty. Postgres and Keycloak read these values only when their volume is
first created. So to change `.env` later, run `docker compose down -v` first, which deletes
all data.

**URLs** (sign-in users: `ada` admin, `sam` supervisor, `ann`/`ben` agents; the password is
`DEMO_USER_PASSWORD` in `.env`):

| URL | What |
|---|---|
| http://localhost:5580 | The helpdesk (the only URL a browser needs) |
| http://localhost:48025 | Mailpit: every email, in and out |
| http://localhost:45673 | RabbitMQ management (user `helpdesk`, password `RABBITMQ_PASSWORD`) |
| http://localhost:8680 | Keycloak admin console (user `admin`, password `KEYCLOAK_ADMIN_PASSWORD`) |
| http://localhost:8580, :8581 | The two API instances directly (debugging; the browser should use 5580) |
| `localhost:45432`, `:45672`, `:41025` | Postgres, RabbitMQ AMQP, Mailpit SMTP |

**All test suites**

```powershell
# API: Checkstyle + javac -Werror + 96 unit/integration tests (Testcontainers: Postgres, RabbitMQ, Mailpit).
# The first run downloads Maven and every dependency into the helpdesk-sla-m2 volume: 11 min from an
# empty cache on a busy machine; later runs about 5 min.
docker run --rm -v "${PWD}\services\helpdesk-api:/workspace" -v helpdesk-sla-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace eclipse-temurin:25-jdk sh mvnw -B verify
```

```powershell
# Web with Node on the host: lint, 14 tests, type-check + build
cd web; npm ci; npm run lint; npm test; npm run build; cd ..
```

```powershell
# Web without Node: the same in a container. The extra volume keeps the Linux node_modules out of
# your folder (Docker leaves an empty web\node_modules folder behind as its mount point; harmless).
docker run --rm -v "${PWD}\web:/app" -v /app/node_modules -w /app node:24-alpine sh -c "npm ci --no-audit --no-fund && npm run lint && npm test && npm run build"
```

**Stop, restart, start over**: `docker compose stop` / `docker compose up -d` keep all data.
`docker compose down -v` deletes the containers *and* the data volumes, including the
Keycloak realm. After that, the next `up` imports the realm and creates the database logins
again. To also get new secrets, delete `.env` and run `init-env.ps1` again.

**If something goes wrong**
- *"running scripts is disabled on this system"*: run the `Set-ExecutionPolicy -Scope Process` line from step 0. It changes nothing outside this window.
- *"required variable ... is missing a value"*: `.env` is missing or has an empty value (step 2).
- *A port is taken*:
  - Ports 8580, 8581, 45432, 45672, 45673, 41025 and 48025 are easy to change: they appear in `docker-compose.yml`, plus 8580 in `web/vite.config.ts` (dev proxy) and 41025/48025 in `scripts\*.ps1`.
  - 5580 and 8680 are also in the Keycloak realm (redirect URIs), the API's `PUBLIC_URL`/issuer settings, `KC_HOSTNAME`, `web/vite.config.ts` and the scripts. Free them rather than moving them.
  - If binding fails although nothing listens, check Windows' reserved ranges with `netsh interface ipv4 show excludedportrange protocol=tcp`.
- *`docker compose up` reports a service unhealthy, or "dependency failed to start"*:
  - The first start is the slow one: Keycloak rebuilds itself for Postgres and imports the realm. The healthchecks allow 10 minutes for Keycloak and 5 for each API instance.
  - Look at `docker compose ps` and `docker compose logs <service>`. If it was only slow, run `docker compose up -d --wait` again once it shows healthy.
  - If it really fails, Docker usually has too little memory.
- *Changed `.env` and now sign-in or the database fails*: the volumes still hold the old secrets. Run `docker compose down -v`, then `up` again.

## Run and verify

The dev machine is Windows (PowerShell 5.1 and Git Bash) with Docker Desktop. **There is no
local JDK or Maven**: Java is built and tested inside Docker.

```powershell
.\scripts\init-env.ps1                 # once; writes .env (git-ignored) with random secrets
docker compose up -d --build --wait    # everything; web on http://localhost:5580
.\scripts\demo.ps1                     # end-to-end demo (~4 min); .\scripts\seed-demo.ps1 -RestoreSla afterwards
```

| Area | Check before you call a change done |
|---|---|
| API | From the repo root: `docker run --rm -v "${PWD}\services\helpdesk-api:/workspace" -v helpdesk-sla-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace eclipse-temurin:25-jdk sh mvnw -B verify` (~5-6 min; Checkstyle + `javac -Xlint:all -Werror` + unit tests + Testcontainers for Postgres, RabbitMQ with STOMP, Mailpit). One class: add `-Dtest=SlaWorkerTests` |
| Web | In `web/`: `npm test`, `npm run lint`, `npm run build` (includes `tsc`) |
| Running stack | `docker compose up -d --build api-1 api-2 web`, then `.\scripts\demo.ps1` (restore with `.\scripts\seed-demo.ps1 -RestoreSla`). Auth, session, CSRF or WebSocket changes: also `.\scripts\smoke-test.ps1` (scripted browser sign-in through Keycloak's form), and an unauthenticated `GET http://localhost:5580/api/me` must be 401 |

Ports (all 127.0.0.1, deliberately non-standard; other projects on this machine hold 5432,
6379, 5173, 1025, 8025 and HRMS/knowledge-assistant/payment-ledger ports): web **5580**,
API **8580** and **8581**, Keycloak **8680**, Postgres **45432**, RabbitMQ **45672** (AMQP)
and **45673** (UI), Mailpit **41025** (SMTP) and **48025** (UI/API). Management/actuator is
8081 inside each API container, never published. Before adding a port, check
`Get-NetTCPConnection -State Listen -LocalPort <port>`.

Demo users (realm `helpdesk`; password is `DEMO_USER_PASSWORD` in `.env`, never repeat it in
chat or docs): `ada` ADMIN+SUPERVISOR+AGENT, `sam` SUPERVISOR+AGENT, `ann` AGENT, `ben` AGENT.
Customers don't sign in; they email `support@helpdesk.test` (General) or `billing@helpdesk.test`.

## Request flow

Browser → `web` (nginx :5580, the only origin the browser uses) → round-robin to `api-1` /
`api-2` for `/api/*`, `/ws`, `/oauth2/*`, `/login/oauth2/*`, `/logout`. Sign-in: the API runs
the code flow with PKCE against Keycloak as the confidential client `helpdesk-web` and keeps
the result in a Spring Session stored in Postgres; the browser only gets the HttpOnly cookie
`HELPDESK_SESSION`. Scripts send `Authorization: Bearer` (tokens from the dev-only
`helpdesk-cli` client), verified per request (issuer, audience `helpdesk-api`). A change runs
in one transaction (lock ticket → rules → change → audit → outbox); the relay publishes to
the `helpdesk.events` topic exchange; consumers (live updates, notifications, customer email)
each have their own queue; the live-update consumer pushes through the STOMP relay (RabbitMQ)
to every browser. Customer email: Mailpit ← poller (Mailpit REST API) → `InboundEmailService`.

## Invariants: do not break these

**Lifecycle and permissions**
- Status changes go through `TicketStatus.after(action)` only; the transition table is the
  nested switch in `TicketStatus.next`. Add a status or action there and let the compiler
  point at every switch to update (no `default` branches on these enums).
- `NEW` ⇔ unassigned (DB CHECK `tickets_new_iff_unassigned`).
- Who may do what lives in `TicketPermissions`; the UI renders exactly `allowedActions` from
  the API. Status codes: **404** doesn't exist, **403** not allowed, **409** state forbids
  it, **422** business rule (`errors[]`), **400** malformed.
- The acting user always comes from the verified token/session (`Actor` method parameter),
  never from a request body.

**SLA clocks and the worker**
- Deadlines are stored (`due_at`, `warn_at`), never in-memory timers. Business-time maths
  lives in `BusinessCalendar` (pure, unit-tested); `SlaTimer` recomputes deadlines only on
  start, pause, resume, meet, reopen and retarget.
- A ticket's row lock guards the whole aggregate (ticket + timers). API writes lock it first
  (`TicketService.lock`, `PESSIMISTIC_WRITE`); background jobs lock with
  `FOR UPDATE ... SKIP LOCKED` and never wait. Keep that order, or deadlocks become possible.
- Handlers re-check state after locking (idempotent). The unique index `audit_events_sla_once`
  must stay: it guarantees one warning/breach record per ticket and metric.
- A breach is dated at the deadline (`breached_at = due_at`), not when the worker ran.
- `Clock` is injected everywhere. Never call `Instant.now()` in business code (the live-update
  hint timestamp is the one cosmetic exception).

**Events and messaging**
- Domain events are written to the outbox inside the business transaction
  (`DomainEvents.publish`, `Propagation.MANDATORY`). Never publish to RabbitMQ directly from
  business code.
- Delivery is at least once: every consumer with side effects deduplicates with
  `ProcessedMessages.firstTime(consumer, eventId)` in the same transaction as its writes.
- `TicketEvent` is sealed; consumers switch over it exhaustively (list ignored types
  explicitly). The event type string is also the routing key; keep `@JsonSubTypes` names and
  `eventType()` in sync.
- Live-update pushes are hints (ids only). Never send ticket contents over the WebSocket.
  `StompSecurityInterceptor`: subscribe only to `/topic/tickets` and your own
  `/topic/agent.<id>`; clients can't SEND.

**Email**
- Dedup by Message-ID (`inbound_emails.message_id` unique, `ON CONFLICT DO NOTHING` first).
- Threading only adds to a ticket when the sender is that ticket's customer.
- Ignore `Auto-Submitted` (≠ no), `Precedence: bulk/junk/list/auto_reply`, our own addresses
  and `X-Helpdesk-Origin`. Our auto-replies set `Auto-Submitted: auto-replied`.
- Outbound Message-IDs derive from the event id (duplicates thread as the same email) and are
  recorded in `email_threads`.
- Message bodies are untrusted text: rendered as plain text in the UI, never as HTML.

**Data and security**
- Flyway migrations are immutable once applied: add `V<n+1>__description.sql`. Hibernate runs
  with `ddl-auto: validate`. New tables get grants for `${appRole}` (see the end of `V1`).
- `audit_events` and `ticket_messages` are append-only (trigger + no UPDATE/DELETE for the
  app login).
- No tokens in the browser; cookie names are app-specific (`HELPDESK_SESSION`,
  `HELPDESK-XSRF-TOKEN`); every state-changing browser request needs `X-XSRF-TOKEN`
  (`apiFetch` adds it). Don't add CSRF exemptions. `/api/**` without a session is 401, never
  a redirect. Redirect URIs and the WebSocket allowed origin come from `helpdesk.public-url`.
- Web CSP (`web/nginx.conf`): no inline scripts/styles, `connect-src 'self'`.
- Secrets only in `.env`; compose uses `${VAR:?...}` so nothing has a default password.

## Conventions

**Java**: constructor injection; records for DTOs and configuration (`HelpdeskProperties`);
services `@Transactional(readOnly = true)` by default; thin controllers; RFC 9457 errors via
`GlobalExceptionHandler`. Scheduled jobs call a public method that runs its own transaction
through `TransactionTemplate` (not `@Transactional` on a self-invoked method, which Spring's
proxy would ignore); tests call those methods directly with `helpdesk.scheduling.enabled=false`.
Spring Boot 4 specifics: `spring-boot-starter-webmvc`, `spring-boot-starter-rabbitmq`
(classic AMQP 0-9-1; `-amqp` is AMQP 1.0), Jackson 3 (`tools.jackson.*`; annotations still
`com.fasterxml.jackson.annotation`), Testcontainers 2 (`org.testcontainers.postgresql`,
`org.testcontainers.rabbitmq`). Checkstyle forbids inner assignments and lines over 130.

**Tests**: plain JUnit for pure logic (`BusinessCalendarTests`, `SlaTimerTests`,
`TicketStatusTests`, `EmailParserTests`); integration tests extend `IntegrationTest` (one
shared context and containers, `MutableClock`, `TestTokens` for bearer tokens). Every test
creates its own users (`TestUsers.agent("ann")` gets a random suffix), queue (`newQueue`) and
tickets, and asserts only on those; the database is shared and never cleaned. Set the clock
at the start of a test that depends on time (`clock.set(MutableClock.MONDAY_10AM_LONDON)`).

**Web**: all HTTP through `apiFetch` and the hooks in `api/hooks.ts`; live updates only
invalidate queries. Named exports, strict TypeScript, Tailwind classes, Vitest + Testing Library.

## Things that already bit us

- **RabbitMQ config keys are validated**: an unknown key in `rabbitmq.conf` (e.g. a made-up
  `stomp.allow_anonymous`) stops the broker at boot. Check the plugin's schema first.
- **The JRE image's `/bin/sh` is dash**: healthchecks using `/dev/tcp` must run `bash -c`.
- **RabbitMQ healthcheck vs. the Erlang cookie**: healthchecks run as root with
  `HOME=/var/lib/rabbitmq`. A `rabbitmq-diagnostics` call that runs before the broker has
  created `.erlang.cookie` creates it as root. The broker (user `rabbitmq`) then fails with
  `eacces`, and on a fresh volume `docker compose up --wait` fails. It only showed up on a
  busy machine (found in the clean-copy check). The healthcheck now waits for the cookie
  file first; keep that guard.
- **First start can be very slow on a busy machine**: Keycloak's one-off "augmentation" took
  252 s instead of seconds while other stacks were building. Hence the long `start_period`s
  in `docker-compose.yml` (10 min for Keycloak, 5 min for the API). They cost nothing on a
  fast machine: the first successful check ends the wait. A dependency that goes unhealthy
  makes plain `docker compose up` fail too, not just `--wait`.
- **`WebSocketStompClient` needs a `TaskScheduler`** to track receipts/heartbeats.
- **SUBSCRIBE only after CONNECTED**: Spring's broker relay drops frames that arrive before
  its connection to RabbitMQ for that session is up. stompjs waits; hand-written clients
  (`watch-live.ps1`, `smoke-test.ps1`) must too.
- **Keycloak sets `Secure` cookies even on http://localhost.** Browsers accept them (localhost
  is a secure context); .NET's cookie jar doesn't send them over http, so `smoke-test.ps1`
  copies them into the jar. .NET's jar also ignores ports: localhost:8680 and :5580 share it.
- **STOMP ERROR frames are generic** ("Failed to send message to ...clientInboundChannel"):
  don't assert on the reason, and don't make the server leak one.
- **`convertAndSend(destination, Map)` is ambiguous** (headers overload): send a record.
- **Spring Data ignores nested repository interfaces** by default: one top-level file each.
- **Postgres `INSERT ... ON CONFLICT` consumes an identity value** even when it inserts
  nothing: read first, upsert only on change (see `AgentDirectory`).
- **Keycloak realm import runs only on first start**, and `${ENV}` placeholders are filled then.
  Change a running realm with `kcadm.sh` (pipe scripts through Git Bash; PowerShell adds a BOM)
  *and* update `helpdesk-realm.json`. Defining realm-level `clientScopes` in the import file
  replaces Keycloak's built-in scopes, so mappers live on the clients instead.
- **Inside Docker, "localhost" is the container**: the API reaches Keycloak at
  `keycloak:8080` (token, JWKS) while browsers use `localhost:8680`; `KeycloakClientConfig`
  builds the registration by hand for that reason.
- **Windows PowerShell 5.1**:
  - `$home` is read-only.
  - `Invoke-RestMethod -Method Post` without a body sends a form content type (`Invoke-Helpdesk` always sends JSON).
  - The web cmdlets wait **forever** by default: one stalled connection on a busy machine hung `smoke-test.ps1` for an hour. It now sets a script-local `$PSDefaultParameterValues` timeout, and its WebSocket waits skip unrelated frames for up to 30 s.
  - Bash heredocs with some quoting fail in this environment, so write files with an editor tool.
- **Demo SLA**: `demo.ps1` shortens the URGENT policy to minutes. Run
  `.\scripts\seed-demo.ps1 -RestoreSla` afterwards.

## Definition of done

1. Tests and linters pass for every part you touched (commands above).
2. Schema changes: a new Flyway migration with grants for `${appRole}`.
3. The running stack is rebuilt and `.\scripts\demo.ps1` passes.
4. README and this file updated if behaviour, commands, ports or decisions changed.
5. Don't commit or push unless the owner asks.

## Learning guide

**How to use it.** Read the topics in order; each builds on the ones before. Every topic gives:
- **What**: the idea in a few sentences;
- **Read**: the files, in the order to open them;
- **Test**: the test that pins the idea down.

Run that test on its own and break the code on purpose to see what it protects. From the repo root:

```powershell
docker run --rm -v "${PWD}\services\helpdesk-api:/workspace" -v helpdesk-sla-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace eclipse-temurin:25-jdk sh mvnw -B test -Dtest=TicketStatusTests
```

Java paths below are relative to `services/helpdesk-api/src/main/java/com/helpdesk/`. Test classes live under the same packages in `src/test/java`.

### 1. One HTTP request, end to end
**What**
- Spring Boot creates your objects ("beans") and connects them by constructor injection: a class lists what it needs in its constructor, and Spring passes it in.
- A request reaches a `@RestController` method. That method calls a service, whose public methods run inside a database transaction (`@Transactional`).
- The service returns a record, and Jackson turns it into JSON.
- Errors are exceptions. `GlobalExceptionHandler` turns them into RFC 9457 "problem details", with deliberate status codes (404/403/409/422/400).

**Read**
- `ticket/TicketController.java`
- `ticket/TicketService.java` (`perform`, `lock`)
- `ticket/TicketDtos.java`
- `common/GlobalExceptionHandler.java`
- `config/HelpdeskProperties.java` with `src/main/resources/application.yml` (typed configuration)

**Test**: `TicketLifecycleApiTests`.

### 2. Modern Java you will meet everywhere
**What**
- **Records** are immutable data classes with no boilerplate: DTOs, events, configuration.
- **Enums with behaviour**: `TicketStatus` decides its own transitions.
- **Switch expressions without `default`**: the compiler insists every constant is handled, so adding one breaks the build until every switch decides what to do with it.
- **Sealed interfaces** (`TicketEvent`): the compiler knows the complete list of subtypes, so a pattern-matching `switch` over an event (`case TicketEvent.Created e -> ...`) is also checked for completeness.
- `_` is an unnamed pattern, for a case that doesn't need the variable.
- Text blocks (`"""`) hold multi-line SQL.

**Read**
- `ticket/TicketStatus.java`
- `events/TicketEvent.java`
- `notification/NotificationListener.java`: the `switch` over events.

### 3. The ticket lifecycle as a state machine
**What**
- A status changes only through `TicketStatus.after(action)`.
- Every legal move is in one table, the nested switch in `next`. An illegal move throws `InvalidTransitionException`, and the API answers 409.
- Four concerns are kept apart:
  - *what* is legal: `TicketStatus`;
  - *who* may do it: `TicketPermissions`;
  - *what the SLA clocks do* in each status: `TicketStatus.slaClock()`;
  - the one invariant the database also enforces: `NEW` ⇔ unassigned, a CHECK constraint.

**Read**
- `ticket/TicketAction.java`
- `ticket/TicketStatus.java`
- `ticket/TicketPermissions.java`
- `ticket/Ticket.java` (`moveTo`, `assignTo`)

**Test**: `TicketStatusTests`, which checks every (status, action) pair.

### 4. Entities, transactions and locks (JPA, Hibernate, Postgres)
**What**
- An `@Entity` is a class mapped to a table. Inside a transaction, Hibernate tracks changes to loaded entities and writes them at commit ("dirty checking"), so there is no explicit `save` after a change.
- The schema belongs to Flyway migrations; Hibernate only validates it at startup.
- Two kinds of locking:
  - **Optimistic** (`@Version`): detect a concurrent edit at commit and answer 409. SLA policies use it (the admin sends back the version they edited), and tickets carry one as a safety net.
  - **Pessimistic** (`SELECT ... FOR UPDATE`): make the other transaction wait. API writes lock the ticket row first, and that one lock protects the ticket *and* its SLA clocks, the "aggregate".

**Read**
- `ticket/Ticket.java`
- `ticket/TicketRepository.java` (`findByIdForUpdate`)
- `TicketService.lock`
- `src/main/resources/db/migration/V1__schema.sql`
- `sla/SlaPolicyController.java` (the `version` check)

### 5. Time zones and business-hours maths
**What**
- An `Instant` is a point on the global timeline. A `LocalDate` or `ZonedDateTime` is what a calendar or wall clock shows in one zone.
- Opening hours are local (09:00-17:00 Europe/London), so `BusinessCalendar` turns each day's window into instants for that day. That is why daylight-saving days still have 8 open hours.
- `addBusinessTime(start, 4h)` walks open windows day by day until the amount is used up. `businessTimeBetween(a, b)` adds up the overlap with open windows. A property test checks that the two agree.
- "Now" always comes from an injected `Clock`, so tests can freeze it and move it.

**Read**
- `sla/BusinessCalendar.java`
- `sla/Calendars.java`
- `config/TimeConfig.java`
- test `MutableClock.java`

**Test**: `BusinessCalendarTests`.

### 6. SLA clocks as stored deadlines
**What**
- There is no in-memory timer. Each clock row stores:
  - the business time already used (`consumed_seconds`);
  - when the current running stretch began (`running_since`);
  - the precomputed instants `due_at` and `warn_at`.
- **Pause**: fold the running stretch into `consumed_seconds` and clear the deadlines.
- **Resume**: set `running_since = now` and compute the deadlines from what's left of the target.
- The worker then needs only one indexed question: "which running clocks have `due_at <= now`?" A restart loses nothing.
- The status → clock mapping lives in `SlaService.onStatusChanged`.

**Read**
- `sla/ClockState.java`
- `sla/SlaTimer.java` (`pause`, `resume`, `foldRunningTime`, `schedule`, `deadline`)
- `sla/SlaService.java`

**Test**: `SlaTimerTests`.

### 7. Background workers that are safe on several instances: SKIP LOCKED
**What**
- Both API instances run the same `@Scheduled` jobs, with no leader election.
- `SELECT ... FOR UPDATE SKIP LOCKED` locks the due rows and *skips* rows that another transaction holds. Each instance gets a different batch, nobody waits, and nobody deadlocks.
- After locking, the worker re-checks the freshly loaded clock: another instance may have just handled it. Doing the same work twice must be harmless ("idempotent").
- A unique index (`audit_events_sla_once`) makes a second breach record impossible even if the code had a bug.
- The same pattern drives the outbox relay, the "next ticket" claim and auto-close.
- The jobs use `TransactionTemplate`, because Spring ignores `@Transactional` on a method called from inside the same class: the call doesn't go through Spring's proxy.

**Read**
- `ticket/TicketRepository.java` (`lockTicketsWithDueTimers`, `lockNextUnassigned`)
- `sla/SlaEscalationWorker.java`
- `ticket/AutoCloseJob.java`
- `config/SchedulingConfig.java`

**Test**
- `SlaWorkerTests`
- `SlaWorkerConcurrencyTests`: 4 workers, 60 due tickets, each escalated exactly once.
- `ClaimNextConcurrencyTests`: 5 agents, never the same ticket.

### 8. The transactional outbox
**What**
- "Update the database, then publish a message" touches two systems.
  - A crash between the two loses the message.
  - A rollback after publishing announces something that never happened.
- The fix: write the event as a row in `outbox_events` in the *same* transaction as the change. `DomainEvents.publish` uses `Propagation.MANDATORY`: it refuses to run outside a transaction.
- A relay job picks unpublished rows (SKIP LOCKED again) and publishes them to RabbitMQ. It waits for the broker's **publisher confirm** and only then marks the row published. On failure it retries later with exponential backoff.
- Result: **at-least-once** delivery. Nothing is lost; duplicates are possible.

**Read**
- `events/TicketEvent.java`
- `events/DomainEvents.java`
- `events/OutboxEvent.java`
- `events/OutboxRepository.java`
- `events/OutboxRelay.java`

**Test**: `MessagingTests`.

### 9. RabbitMQ: exchanges, queues, retries, dead letters
**What**
- A publisher sends to an **exchange** with a **routing key** (here the event type, e.g. `sla.breached`).
- **Bindings** copy each message to every **queue** whose pattern matches. Each consumer (live updates, notifications, customer email) has its own queue.
- The two API instances are **competing consumers** on each queue: every message goes to one of them.
- When a listener throws, Spring retries it in process with backoff. After the last attempt the message is rejected without requeueing, and the queue's **dead-letter exchange** sends it to `<queue>.dlq` for a human to look at.
- Delivery is at least once. So a consumer with side effects records the event id in `processed_messages`, in the same transaction as its own writes, and skips ids it has already seen (an **idempotent consumer**).

**Read**
- `messaging/RabbitTopology.java`
- `messaging/EventMessages.java`
- `messaging/ProcessedMessages.java`
- `notification/NotificationListener.java`
- `spring.rabbitmq` in `application.yml`

**Test**: `MessagingTests`: redelivery notifies once; an unreadable message lands in the DLQ.

**See it**: http://localhost:45673 → Queues.

### 10. WebSockets and STOMP
**What**
- A WebSocket is a long-lived, two-way connection. **STOMP** is a small text protocol on top of it, with frames (CONNECT, SUBSCRIBE, MESSAGE, ERROR) and destinations such as `/topic/tickets`.
- Spring's **broker relay** forwards subscriptions and messages to RabbitMQ's STOMP plugin. So a push sent by `api-1` reaches a browser connected to `api-2`.
- Pushes are *hints* (event type + ticket id). The browser refetches through the REST API, so permissions are applied in one place.
- `StompSecurityInterceptor` checks every SUBSCRIBE and refuses SEND. The handshake checks the `Origin` header, which blocks cross-site WebSocket hijacking.

**Read**
- `config/WebSocketConfig.java`
- `live/StompSecurityInterceptor.java`
- `live/LiveUpdateListener.java`
- `live/AgentTopics.java`
- `web/src/live/useLiveUpdates.ts`
- `scripts/watch-live.ps1`: STOMP written by hand.

**Test**: `LiveUpdateTests`.

### 11. Email to ticket
**What**
- The poller lists new messages in Mailpit and downloads the raw email bytes (RFC 5322). It hands them to `InboundEmailService.ingest`, which:
  1. **claims the Message-ID first** (`INSERT ... ON CONFLICT DO NOTHING`): only the first claimant goes on, so a retry or a second poller can't make a second ticket;
  2. **ignores automatic mail** (auto-replies, bulk mail, our own messages), which prevents mail loops;
  3. **finds the conversation**: first through `In-Reply-To`/`References` matched against `email_threads`, then through `[HD-n]` in the subject, and only if the sender is that ticket's customer;
  4. **appends** a message (a customer reply resumes the SLA clock) or **creates** a ticket.
- `EmailParser` (Jakarta Mail) extracts plain text, converts HTML-only mail, and drops quoted history.
- Outgoing mail gets a Message-ID derived from the event id and recorded in `email_threads`, so replies to it thread correctly.

**Read**
- `mail/inbound/MailpitMailbox.java`
- `mail/inbound/InboundMailPoller.java`
- `mail/inbound/InboundEmailService.java`
- `mail/inbound/EmailParser.java`
- `mail/EmailThreads.java`
- `mail/OutboundMailer.java`
- `mail/CustomerEmailListener.java`

**Test**: `EmailParserTests`, `EmailIntakeTests`.

**Try it**: `.\scripts\send-email.ps1 -Subject "..." -Body "..."`, then look in Mailpit.

### 12. Sign-in: OIDC, the BFF pattern, CSRF, bearer tokens
**What**
- **OIDC authorization code flow with PKCE**:
  1. the browser is sent to Keycloak, and the user signs in there;
  2. Keycloak redirects back with a one-time code;
  3. the server, a *confidential* client with a secret, exchanges the code for tokens.
- Here the tokens stay on the server, in an HTTP session stored in Postgres (Spring Session JDBC), so both instances share it. The browser holds only an HttpOnly cookie. This is the **backend-for-frontend** pattern.
- Browsers send cookies automatically, even on requests another site triggers. So every state-changing request must also carry a **CSRF token**: the server sets it in a cookie that only this origin can read, and the app sends it back as a header.
- Scripts use a second filter chain: `Authorization: Bearer <JWT>`, verified on every request (signature through Keycloak's JWKS keys, issuer, audience `helpdesk-api`).
- Either way, controllers receive an `Actor`, backed by the agent row that `AgentDirectory` creates on first sign-in.

**Read**
- `config/SecurityConfig.java`
- `config/KeycloakClientConfig.java`
- `security/RolesClaim.java`
- `security/AgentDirectory.java`
- `security/ActorArgumentResolver.java`
- `infra/keycloak/helpdesk-realm.json`
- `web/src/auth/session.ts`
- `web/src/api/client.ts`

**Test**: `BearerTokenTests`, `TicketLifecycleApiTests` (401 and CSRF cases), and the script `scripts/smoke-test.ps1`.

### 13. The database as the last line of defence
**What**
- Two logins: `helpdesk_owner` runs migrations; the app's login `helpdesk_app` gets only data access and can't UPDATE or DELETE history.
- Triggers reject changes to `audit_events` and `ticket_messages`.
- CHECK constraints and unique indexes encode the rules that must hold even if the Java code is wrong.

**Read**
- `infra/postgres/init/01-init.sh`
- `V1__schema.sql`: the constraints, `audit_events_sla_once`, `reject_history_change`, and the grants at the end
- `audit/AuditService.java`

**Test**: the history test in `TicketLifecycleApiTests`.

### 14. Testing with real infrastructure
**What**
- Unit tests for pure logic run in milliseconds.
- Integration tests start real Postgres, RabbitMQ (with STOMP) and Mailpit in Docker through **Testcontainers**: once, shared by all test classes through one Spring context.
- The database is never cleaned. Each test creates its own users, queue and tickets, and asserts only on those.
- Time is a `MutableClock` that the test moves by hand. Tokens are signed with a test key (`TestTokens`).
- **Awaitility** waits for asynchronous results (messages, emails) instead of `Thread.sleep`.

**Read** (all in `src/test/java/com/helpdesk/`)
- `TestcontainersConfiguration.java`
- `IntegrationTest.java`
- `MutableClock.java`
- `TestTokens.java`
- `TestUsers.java`
- then any `*Tests` class

### 15. The web app
**What**
- Components get server data through TanStack Query hooks (`api/hooks.ts`), which cache responses by key.
- A live update **invalidates** the matching keys, and TanStack Query refetches them.
- Every request goes through `apiFetch`, which adds the CSRF header, turns error responses into `ApiError`, and sends a 401 to sign-in once.
- The ticket page shows exactly the `allowedActions` the server returns, and decides no permissions itself.

**Read** (in `web/src/`)
- `main.tsx`
- `App.tsx`
- `auth/AuthGate.tsx`
- `api/client.ts`
- `api/hooks.ts`
- `pages/TicketPage.tsx`
- `live/useLiveUpdates.ts`
- `components/SlaBadge.tsx`

**Test**: `pages/TicketPage.test.tsx`, `auth/session.test.ts`.

### 16. Running it like production
**What**
- Two API containers run behind nginx, which round-robins requests and upgrades `/ws` to a WebSocket.
- Healthchecks control the start order.
- Multi-stage Dockerfiles: a JDK image builds, a JRE image runs, as a non-root user, with dependencies in their own layer.
- Secrets live only in `.env`, and every port is bound to 127.0.0.1.
- CI builds the images and scans them with Trivy.

**Read**
- `docker-compose.yml`
- `services/helpdesk-api/Dockerfile`
- `web/Dockerfile`
- `web/nginx.conf`
- `.github/workflows/ci.yml`

### Glossary

| Term | Meaning here |
|---|---|
| SLA | Service-level agreement: the promised response and resolution times, per priority (`sla_policies`) |
| First response / resolution | The two clocks per ticket: until the first public agent reply / until the ticket is resolved |
| Business time | Time that counts toward an SLA: inside a calendar's opening hours, not on holidays |
| Warning / breach | A clock has used its warning share (75%) of the target / passed its deadline |
| Escalation level | How many breaches a ticket has had; escalated tickets are highlighted and supervisors are told |
| Aggregate | A group of rows changed together under one lock: here a ticket and its SLA clocks |
| State machine | A fixed set of states plus the moves allowed between them (`TicketStatus.next`) |
| Optimistic / pessimistic locking | Detect a conflicting edit at commit (`@Version`) / prevent it by locking the row first (`FOR UPDATE`) |
| `SKIP LOCKED` | Postgres option that leaves rows already locked by others out of the result instead of waiting for them |
| Idempotent | Doing it twice has the same effect as once |
| At-least-once delivery | Every message arrives, possibly more than once; the receiver must handle duplicates |
| Transactional outbox | Events written to a table in the business transaction, published later by a relay |
| Publisher confirm | RabbitMQ's acknowledgement that it has taken responsibility for a published message |
| Exchange / routing key / binding / queue | RabbitMQ's post office / address on the message / forwarding rule / mailbox a consumer reads |
| Competing consumers | Several instances reading the same queue; each message goes to one of them |
| DLX / DLQ | Dead-letter exchange / queue: where messages go after their retries are used up |
| STOMP, frame, destination | Text messaging protocol over WebSocket; one message of it; the "address" you subscribe to |
| Broker relay | Spring forwarding STOMP traffic to a real broker (RabbitMQ) instead of keeping it in memory |
| OIDC, authorization code, PKCE | Sign-in protocol on top of OAuth 2; the one-time code exchanged for tokens; a proof that the same client started and finished the sign-in |
| Confidential client | An OAuth client that can keep a secret (a server), unlike a browser app |
| JWT, issuer, audience | Signed token; who signed it; who it is meant for (`helpdesk-api`) |
| BFF | Backend-for-frontend: the server signs in and keeps the tokens; the browser only has a session cookie |
| CSRF | Cross-site request forgery: another site making your browser send a request with your cookies |
| HttpOnly / SameSite=Lax | Cookie flags: JavaScript can't read it / not sent on most cross-site requests |
| Message-ID, In-Reply-To, References | Email headers that identify a message and the messages it answers (how threading works) |
| Auto-Submitted, Precedence | Headers marking automatic or bulk mail; used to avoid auto-reply loops |
| Flyway migration | A versioned SQL file applied once, in order; never edited after it has run |
| RFC 9457 problem detail | Standard JSON error body (`type`, `title`, `status`, `detail`, plus `errors[]` here) |
| Testcontainers | Library that starts throwaway Docker containers for tests |
| Mailpit | A local mail server with a web UI and API; stands in for a real mailbox |
| Keycloak realm | An isolated set of users, roles and clients in Keycloak (`helpdesk`) |

### Exercises

Each one runs from the commands in [Run and verify](#run-and-verify). Write down what you expect before you run anything.

1. **Predict a deadline.**
   - The scenario: a NORMAL ticket (4 business hours, office-hours calendar) arrives on Thursday 2026-12-24 at 15:00 London time. Look at the bank holidays in `V2__reference_data.sql`.
   - Work out by hand when it is due.
   - Then add that case to `BusinessCalendarTests` and run it.
   - Extra: a ticket arriving at 08:30 on the Sunday when the clocks go forward (2027-03-28).
2. **Add a status `WAITING_ON_THIRD_PARTY`** (the clock pauses, as it does while waiting on the customer), with the action `WAIT_ON_THIRD_PARTY`; `RESUME` goes back to `OPEN`.
   - Add the constant and let the compiler list every switch you must decide.
   - Widen the `tickets.status` CHECK constraint in a new `V3__...sql`. Never edit `V1`.
   - Extend `TicketStatusTests`, then add the action to `web/src/api/types.ts` and `actionLabel` in `web/src/lib/format.ts`.
   - Check: `mvnw verify` and `npm test` pass, and the button appears only when the server allows it.
3. **Write the housekeeping job** (Status, item 2).
   - Delete published `outbox_events` rows older than 7 days, in batches, with SKIP LOCKED.
   - Test it with `MutableClock`.
   - Then explain in a comment why `processed_messages` must be kept longer than a message can wait in a DLQ.
4. **A new consumer: email the customer when their ticket is resolved.**
   - Handle `TicketEvent.StatusChanged` to `RESOLVED` in `CustomerEmailListener`; today it is ignored there.
   - Derive the Message-ID from the event id, and add a test proving that a redelivered event sends one email.
   - Then reply to that email from `send-email.ps1 -InReplyTo ...` and watch the ticket reopen (the `CUSTOMER_REPLIED` transition).
5. **Break it on purpose.**
   - In `TicketRepository.lockNextUnassigned`, replace `FOR UPDATE SKIP LOCKED` with plain `FOR UPDATE`. Predict what `ClaimNextConcurrencyTests` will show, run it, and explain the result. Hint: read the Postgres docs on `SELECT ... FOR UPDATE` with `LIMIT` under READ COMMITTED.
   - Do the same with the re-check in `SlaEscalationWorker` and `SlaWorkerConcurrencyTests`.
   - Restore the code afterwards.
