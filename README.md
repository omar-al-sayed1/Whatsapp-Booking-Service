# Clinic Reminders

WhatsApp reminders and bookings for private clinics in Lebanon (Zahle, Chtaura, the Bekaa).

Patients get a reminder the day before and 2 hours before each appointment, with **Confirm** and
**Cancel** buttons, and can book, check or cancel appointments on WhatsApp without installing
anything. The front desk works from a simple web dashboard in Arabic or English. A monthly report
on the Report page shows the owner what the service saved.

It is sold as a done-for-you service (a setup fee plus a monthly fee): one deployment serves many
clinics, each with its own WhatsApp number. The full requirements are in [docs/SPEC.md](docs/SPEC.md).

## What it does

**For patients (WhatsApp only)**
- A menu: **Book · My appointment · Info**.
- Booking in about five messages: doctor, day, time, then who the appointment is for. A family
  can share one phone; "Someone else" adds a family member by name.
- Reminders 24 hours and 2 hours before, with Confirm and Cancel. Quiet hours are respected.
- An "Add to my calendar" link in every WhatsApp booking confirmation: a public page in the
  patient's language with an iCalendar file and a Google Calendar button.
- **STOP** or **توقف** opts the number out for good.

**For the front desk (owner and secretary)**
- **Today**: each doctor's appointments with status chips. Mark arrived or no-show, move, cancel,
  squeeze someone in. The list refreshes every 30 seconds.
- **Week**: a grid per doctor, with drag and drop on desktop and touch.
- **Patients**: search by name or any part of the phone number, see family members on the same
  number, book again, delete on request.
- **Needs attention** (the bell): conversations waiting for a call, and messages that failed to send.
- **Doctors**: weekly hours, days off and special hours, and optional logins for doctors.
  - Closing a day that already has bookings shows who is booked first.
  - Those patients can be messaged to book another time, or their appointments kept.
- **Report**: what became of the month's appointments:
  - slots saved by early cancellations, and how many were rebooked
  - no-shows and the no-show rate
  - confirmations, reminders sent, and bookings made on WhatsApp
  - **busy hours** (a weekday × hour heatmap)
  - WhatsApp messages and their estimated cost
  - a CSV export for Excel

**For doctors with their own login**
- Only their own appointments, patients, hours and report.

**For the operator (you)**
- **Clinics**: create a clinic, connect its WhatsApp number (the access token is encrypted at rest),
  subscribe to webhooks, send a test message, and manage logins.
- **Patients**: every clinic's patients, with advanced filters.
- **Report**: every clinic's month side by side, plus each clinic's report and CSV to send them.
- **Message rates**: the per-message prices from Meta's rate card that cost estimates use.

## Status

| Milestone | | |
| --- | --- | --- |
| M0 Scaffold, M1 Domain and slot engine | done | |
| M2 WhatsApp client and webhook | done | |
| M3 Reminders | done | |
| M4 Dashboard | done | Redesigned since, with Week drag and drop and doctor logins |
| M5 WhatsApp booking | done | Families on one number added later |
| M6 Report and costs | done | Busy hours and calendar links added |
| M8 Demo and onboarding | done | |
| **M7 Deploy** | **not started** | Dockerfile, Caddy (HTTPS), VPS, backups, alerts. **Needed before a real pilot**: Meta sends webhooks only to a public HTTPS address. |

## Stack

Java 21 · Spring Boot 4.0 · Spring Security (form login) · Spring Data JPA · PostgreSQL 16 ·
Flyway · Thymeleaf + htmx 2 (no SPA, no Node build) · Spring `RestClient` for the WhatsApp Cloud
API · JUnit 5, AssertJ, Testcontainers.

## Getting started

You need **JDK 21 or newer** and **Docker** (for the local database and the integration tests).

```bash
docker compose up -d db
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev"
```

On Windows, use `mvnw.cmd` from cmd or PowerShell, or `./mvnw` from Git Bash, with `JAVA_HOME`
pointing to JDK 21+.

The **dev profile** uses the compose database and a **fake WhatsApp client**: nothing leaves your
machine.

| Address | What |
| --- | --- |
| http://localhost:8080/ | The dashboard |
| http://localhost:8080/operator | Operator pages |
| http://localhost:8080/dev/whatsapp | WhatsApp simulator: see what was sent, reply as a patient, tap buttons |
| http://localhost:8080/actuator/health | Health check |

The dev profile creates a "Dev Clinic" with these logins. They exist only in the dev profile.

| Username | Password | Sees |
| --- | --- | --- |
| `owner` | `dev` | The whole clinic |
| `secretary` | `dev` | The whole clinic |
| `doctor` | `dev` | Dr. Dev's own appointments, patients, hours and report |
| `operator` | `dev` | The operator pages |

### Demo clinic for sales

"Dr. Demo" has two doctors, a dozen patients (including a family on one number) and four weeks of
appointments around today. It also comes with past reminders and confirmations, a conversation
waiting for the secretary and a failed send, so every page has something to show.

```bash
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--seed-demo"
```

It prints the `drdemo` login and exits. Running it again refreshes the demo around the new day and
never touches a real clinic.

### First operator login on a new server

```bash
java -jar app.jar --create-operator=<username>
```

This prints a generated password once and exits. Running it again gives that operator a new password.

## Tests

```bash
./mvnw verify
```

This runs everything. The `*IT` classes use Testcontainers, so Docker must be running. For unit
tests only (`*Test`), without Docker, run `./mvnw test`. CI runs `./mvnw verify` on every push.

## Configuration

All configuration comes from environment variables (see [.env.example](.env.example)); nothing
secret is committed. The dev profile fills in local defaults.

| Variable | What |
| --- | --- |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | PostgreSQL connection |
| `WA_APP_SECRET` | The Meta app's secret: every webhook is checked against its `X-Hub-Signature-256` |
| `WA_VERIFY_TOKEN` | Any random string, entered again when registering the webhook with Meta |
| `WA_GRAPH_VERSION` | Graph API version, e.g. `v26.0` |
| `TOKEN_ENCRYPTION_KEY` | Base64 256-bit key: clinics' WhatsApp access tokens are encrypted with AES-GCM |
| `APP_BASE_URL` | Public address, e.g. `https://reminders.example.com`; links sent to patients start with it |
| `OPERATOR_ALERT_PHONE` | For operator alerts (M7; not used yet) |

## Connecting a clinic's WhatsApp

Done once:
- Register the webhook on your Meta app: `{APP_BASE_URL}/webhooks/whatsapp`, with `WA_VERIFY_TOKEN`.
- Enter Meta's rates on **Operator → Message rates**. Until then, costs show "—"; prices are
  never guessed.

For each clinic:
1. In Meta, the clinic needs a WhatsApp Business Account and a dedicated phone number, and you
   need a long-lived access token for it.
2. **Submit these templates** in that account, in Arabic and English. Templates belong to each
   business account, so every clinic needs its own approved set. The wording is in
   [SPEC.md section 6](docs/SPEC.md).
   - `appointment_reminder`: the day before, with Confirm and Cancel buttons
   - `appointment_reminder_soon`: 2 hours before, with Confirm and Cancel buttons
   - `appointment_cancelled_by_clinic`: the doctor is away, with a Book another time button
3. On **Operator → Clinics**:
   - create the clinic: name, time zone, language, appointment length
   - paste the phone number id, business account id and token
   - subscribe to webhooks
   - send a test message
   - create the owner's login; its password is shown once
4. The clinic adds its doctors and hours on **Doctors**, and its address and hours (sent to
   patients who tap Info) on **Settings**.

Meta's own pages ("Get started", "Webhooks", "Message templates", the pricing rate card) are the
reference for anything on their side.

## Code layout

Packages by feature under `app.clinicreminders`:

| Package | What |
| --- | --- |
| `clinic` | Clinics, their settings, the demo clinic |
| `staff` | Logins, roles (operator, owner, secretary, doctor), security |
| `scheduling` | Doctors, weekly hours, days off, the slot engine |
| `patient` | Patients (families share a number), search, deletion on request |
| `appointment` | Booking, moving, cancelling; the calendar file |
| `reminder` | Planning and sending reminders, Confirm/Cancel replies, notices to patients |
| `booking` | The WhatsApp booking conversation |
| `whatsapp` | Cloud API and fake clients, webhook inbox, message log, cost estimates and rates |
| `report` | The monthly report, busy hours, CSV |
| `dashboard` | Web controllers and views (Thymeleaf + htmx) |
| `common` | Shared bits: languages, phone numbers, configuration |

Database migrations are in `src/main/resources/db/migration` (Flyway).

## Rules that matter

These come from [CLAUDE.md](CLAUDE.md), which has the full list.

- **Multi-tenancy:** every clinic-owned table has `clinic_id`, and every query filters by it.
  `TenantRepositoryConventionTest` fails on any repository method without a `clinicId`, unless the
  method is marked `@CrossTenant` with a reason.
- **Privacy:** only the patient's name, phone, WhatsApp user id, language and appointment times are
  stored. No medical notes, diagnoses or reasons for visit. Patients can be deleted on request.
- **Time:** stored in UTC, shown in the clinic's zone. Lebanon changes clocks, and that is tested.
- **Reminders** are sent at most once, even across restarts or with two instances running.
- **Webhooks** are signature-checked, processed asynchronously and deduplicated.
- **Every message** goes through one client, is logged in `message_log`, and carries a cost estimate.
- **Migrations are append-only:** never edit one that has been applied.
- **Every text** shown to users is in `messages_en.properties` and `messages_ar.properties`, and the
  dashboard supports right-to-left layout.
