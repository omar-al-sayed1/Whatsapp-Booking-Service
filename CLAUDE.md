# Clinic Reminders: project guide for Claude Code

## What this is

A WhatsApp booking-and-reminder service for private clinics in Lebanon (Zahle, Chtaura, the Bekaa).
It is sold as a done-for-you service: a setup fee plus a monthly retainer. One deployment serves
many clinics (multi-tenant). The full requirements are in `docs/SPEC.md`. Read it before starting
any milestone.

## How to work with me

- I'm Omar, a Java/Spring Boot developer. Explain non-obvious decisions briefly and skip the basics.
- Work one milestone at a time (SPEC.md section 9). At the end of each milestone, run the full test
  suite, summarize what changed, list anything stubbed or postponed, and stop for my review.
- Ask before adding any dependency that is not listed under Stack.
- Never invent WhatsApp Cloud API details. If you're unsure about an endpoint, payload or webhook
  field, say so and tell me which Meta docs page to check.

## Stack

- Java 21, Spring Boot 4.0.x, Maven wrapper
- PostgreSQL 16, Flyway migrations, Spring Data JPA
- Spring Security (form login for the dashboard)
- Thymeleaf + htmx for the dashboard (no SPA, no Node build)
- Spring `RestClient` for the WhatsApp Cloud API (Meta Graph API)
- JUnit 5, AssertJ, Testcontainers (PostgreSQL)
- Docker Compose for local Postgres; Docker + Caddy on a small VPS in production

If a library isn't compatible with Spring Boot 4 yet, tell me before downgrading anything.

## Commands

- Start the database: `docker compose up -d db`
- Run in dev mode (fake WhatsApp): `./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev"`
- Run all tests: `./mvnw verify` (needs Docker: `*IT` classes use Testcontainers)
- Unit tests only, no Docker: `./mvnw test` (`*Test` classes)
- Health check: `http://localhost:8080/actuator/health`
- Dashboard: `http://localhost:8080/`. Dev profile logins: `owner` / `dev`, `secretary` / `dev`,
  `doctor` / `dev` (Dr. Dev's own login: his appointments, patients and hours only) and `operator` / `dev`
  (created by `DevDataInitializer`, dev profile only)
- Operator pages (onboarding clinics): `http://localhost:8080/operator`
- First operator login on a new server: `java -jar app.jar --create-operator=<username>` (locally:
  `./mvnw spring-boot:run "-Dspring-boot.run.arguments=--create-operator=<username>"`). Prints a
  generated password once and exits; running it again gives that operator a new password.
- Demo clinic for sales demos: add `--seed-demo` the same way (with the dev profile locally, e.g.
  `./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--seed-demo"`).
  Creates or refreshes "Dr. Demo" around today, prints the `drdemo` login and exits. Commands run on a
  random port without schedulers, so they work while the app is running.
- WhatsApp simulator (dev profile only): `http://localhost:8080/dev/whatsapp`
- Webhook URL to register with Meta: `{APP_BASE_URL}/webhooks/whatsapp` (verify token = `WA_VERIFY_TOKEN`)
- On Windows use `mvnw.cmd` from cmd/PowerShell, or `./mvnw` from Git Bash; `JAVA_HOME` must point to
  JDK 21+ (CI uses Temurin 21).

Update this section whenever the commands change.

## Code layout

Package by feature under `app.clinicreminders`:
`clinic`, `staff`, `scheduling` (slot engine), `patient`, `appointment`, `whatsapp` (client,
webhook, templates), `reminder`, `booking` (conversation flow), `dashboard` (web controllers and
views), `report`, `common`.

## Rules that matter

- **Multi-tenancy:** every tenant-owned table has `clinic_id`, and every repository query filters
  by it. Add a test whenever you add a query.
- **Time:** store `timestamptz` in UTC; compute and display in the clinic's zone (default
  `Asia/Beirut`). Lebanon observes daylight saving time, so slot and reminder logic must be tested
  across the changeover.
- **Privacy:** store only the patient's name, phone (E.164), WhatsApp business-scoped user ID
  (needed when a webhook omits the phone number), language preference and appointment times. Never store medical notes, diagnoses or reasons for visit. Patients can be deleted on
  request.
- **Secrets:** never commit tokens. Per-clinic WhatsApp access tokens are encrypted at rest
  (AES-GCM, key from an environment variable). All configuration comes from environment variables.
- **Webhooks:** verify `X-Hub-Signature-256` with the app secret before processing anything,
  return 200 quickly and process asynchronously, and handle duplicate deliveries idempotently
  (dedupe on the WhatsApp message id).
- **Sending:** every outbound message goes through one `WhatsAppClient` interface with two
  implementations: `CloudApiWhatsAppClient` (real) and `FakeWhatsAppClient` (dev profile; logs
  messages and exposes a dev page to simulate patient replies). Log every send in `message_log`.
- **Costs:** since 1 October 2026, Meta charges for service messages and utility templates even
  inside the 24-hour customer service window. Keep conversations short and record an estimated
  cost for every message, per clinic.
- **Idempotent reminders:** a reminder is sent at most once, even after a restart or with two
  instances running (`SELECT ... FOR UPDATE SKIP LOCKED`).
- **Opt-out:** if a patient replies STOP or توقف, mark them opted out and never message them again.
- **Migrations are append-only.** Never edit an applied Flyway migration.
- **Languages:** user-facing text lives in `messages_en.properties` and `messages_ar.properties`,
  and the dashboard supports right-to-left layout.

## Definition of done (every milestone)

`./mvnw verify` passes, new logic has unit tests, there are no secrets in code, and you've listed
anything you stubbed or postponed.
