# Clinic Reminders: MVP specification

## 1. Goal

Clinics lose money when patients miss appointments, and secretaries spend hours answering WhatsApp.
The MVP sends WhatsApp reminders with Confirm and Cancel buttons, lets patients book over WhatsApp,
and gives the secretary a simple daily dashboard.

Success for the first pilots: reminders go out reliably for 3 clinics for 30 days with no manual
intervention from the operator.

## 2. Users

| User | Uses | Needs |
| --- | --- | --- |
| Patient | WhatsApp only, never installs anything | Book, confirm or cancel in a few taps |
| Secretary | Web dashboard on desktop or phone, Arabic or English | One clean daily list, quick manual entry |
| Doctor / owner | Monthly report | Proof that the service saves appointments |
| Operator (Omar) | Super-admin pages | Onboard clinics and their WhatsApp numbers |

## 3. Out of scope for the MVP

Payments, medical records, AI chat, multi-branch clinics, native mobile apps, calendar sync and SMS
fallback.

## 4. Data model

All tenant-owned tables carry `clinic_id`. Timestamps are `timestamptz` in UTC.

| Table | Key columns |
| --- | --- |
| `clinic` | id, name, timezone (default `Asia/Beirut`), default_language (`ar`/`en`), address, hours_text, wa_phone_number_id, wa_business_account_id, wa_access_token_encrypted, quiet_hours_start (21:00), quiet_hours_end (08:00), reminder_h24_enabled, reminder_h2_enabled |
| `staff_user` | id, clinic_id (null for operator), username, password_hash, role (`OPERATOR`, `OWNER`, `SECRETARY`), language |
| `doctor` | id, clinic_id, display_name, slot_minutes, active |
| `schedule_rule` | id, doctor_id, day_of_week, start_time, end_time |
| `schedule_exception` | id, doctor_id, date, closed (bool), start_time, end_time |
| `patient` | id, clinic_id, name, phone_e164, language, opted_out, created_at. Unique (clinic_id, phone_e164, lower(name)): a family shares one number, each under their own name; opting out is the number's |
| `appointment` | id, clinic_id, doctor_id, patient_id, starts_at, ends_at, status (`BOOKED`, `CONFIRMED`, `CANCELLED`, `NO_SHOW`, `ARRIVED`), source (`WHATSAPP`, `MANUAL`), cancelled_at, created_at. No two active appointments for the same doctor and start time |
| `reminder` | id, clinic_id, appointment_id, kind (`H24`, `H2`), due_at, status (`PENDING`, `SENT`, `SKIPPED`, `CANCELLED`, `FAILED`), attempts, sent_message_id |
| `message_log` | id, clinic_id, appointment_id (nullable), direction (`IN`, `OUT`), wa_message_id (unique), kind (`TEMPLATE`, `INTERACTIVE`, `TEXT`, `BUTTON_REPLY`), template_name, pricing_category, status (`SENT`, `DELIVERED`, `READ`, `FAILED`), est_cost_usd, created_at. Store no message bodies beyond what debugging needs |
| `conversation` | id, clinic_id, patient_phone, state, context_json, needs_human (bool), expires_at |
| `message_rate` | market, pricing_category, usd_per_message, valid_from (filled from Meta's rate card) |

## 5. Flows

### 5.1 Reminders

1. When an appointment is created or rescheduled, create `H24` and `H2` reminder rows. If a booking
   is made less than 24 hours ahead, skip `H24`.
2. Quiet hours (default 21:00 to 08:00, clinic time): an `H24` reminder due inside them moves to
   the latest allowed time before; an `H2` reminder due inside them is skipped.
3. A scheduler runs every minute, claims due `PENDING` reminders with `FOR UPDATE SKIP LOCKED`,
   sends the template, and records the WhatsApp message id.
4. Never send for cancelled appointments or opted-out patients. Cancelling or moving an appointment
   cancels its pending reminders.
5. Button replies: **Confirm** sets the appointment to `CONFIRMED`; **Cancel** sets it to
   `CANCELLED`, frees the slot, shows it on the dashboard and sends a short acknowledgment.

### 5.2 Booking over WhatsApp

A small state machine per conversation:

`MENU` → `CHOOSE_DOCTOR` (skipped if the clinic has one doctor) → `CHOOSE_DAY` → `CHOOSE_SLOT` →
`CONFIRM` → `DONE`, plus `MY_APPOINTMENT` (see or cancel the next appointment) and `INFO` (hours
and location).

- Use interactive list and reply-button messages. Show the next 7 days that have free slots.
  Check Meta's current limits on list rows and button counts before designing the screens.
- A conversation expires after 30 minutes of inactivity and restarts at `MENU`.
- Double booking is impossible: the final insert is guarded by the database constraint, and if the
  slot was taken in the meantime the patient is offered the next free slots.
- Unrecognized text: send the menu once, set `needs_human`, and show it on the dashboard so the
  secretary can call the patient.
- Families: a parent books for their children from one phone. `CONFIRM` asks who the appointment
  is for (the number's patients, or "Someone else", whose name is typed in `ENTER_NAME`), and My
  appointment shows the appointments of everyone on the number.
- Every step costs money since October 2026, so keep the whole booking to about 5 messages.

### 5.3 Dashboard (Thymeleaf + htmx)

- Login per staff user, scoped to one clinic.
- **Today:** appointments per doctor with status chips; actions to add, reschedule, cancel, mark
  arrived and mark no-show. Refreshes every 30 seconds.
- **Week:** a simple grid per doctor.
- **Patients:** search by name or phone; delete on request (also removes their conversations and
  anonymizes their message log).
- **Needs attention:** conversations flagged for a human, and failed sends.
- **Settings:** doctors, weekly hours, slot length, closed days, reminder toggles, language.
- Arabic and English, with right-to-left layout for Arabic.

### 5.4 Monthly report

Per clinic per month: appointments, reminders sent, confirmations, cancellations at least 2 hours
ahead ("slots saved"), no-shows and estimated message cost. Shown in the dashboard with a CSV
export. A WhatsApp summary to the doctor comes after the MVP.

As built (M6), every appointment figure is about the appointments starting in the month, in the
clinic's zone; messages are the ones sent in it:

- Slots saved: cancelled by the patient on WhatsApp at least 2 hours ahead, and how many of those
  slots someone else booked again. Late cancellations, cancellations by the clinic, appointments
  still to come and past ones nobody marked are counted apart.
- Confirmations use `appointment.confirmed_at` (V9), as the status moves on to arrived or no-show.
- The no-show rate is no-shows out of arrived plus no-shows.
- Busy hours: per weekday and hour, appointments booked against the slots the doctors' current
  weekly hours (with the month's days off) offered.
- Messages by pricing category with their estimates; the operator enters Meta's rates on
  /operator/rates (never guessed), and sees every clinic's month on /operator/report.
- A doctor's own login sees only their appointments, without the clinic's messages.
- The CSV has a byte order mark so Excel shows Arabic, and neutralises cells that start like a formula.

Patients also get an "Add to my calendar" link (V10 `appointment.calendar_token`, random) in the
WhatsApp booking confirmation: a public page with the clinic, doctor and time (never the patient)
and an iCalendar file. The secretary can copy it from an appointment to send to a patient booked by
phone.

### 5.5 Operator onboarding

A super-admin page to create a clinic, set its timezone and language, store its WhatsApp phone
number id, business account id and access token (encrypted), and send a test message.
A seed command creates a "Dr. Demo" clinic with sample doctors, hours and appointments for sales
demos.

## 6. WhatsApp integration

- Meta WhatsApp Cloud API through the Graph API. The Graph API version is configurable
  (`WA_GRAPH_VERSION`); confirm the current version in Meta's docs before coding against it.
- Webhook: `GET` for verification (verify token) and `POST` for messages and status updates,
  signature-checked as described in CLAUDE.md.
- Templates (category UTILITY), submitted in Arabic and English:

| Name | When | Body (English) | Buttons |
| --- | --- | --- | --- |
| `appointment_reminder` | 24 hours before | Hello {{1}}, this is a reminder of your appointment with {{2}} tomorrow, {{3}} at {{4}}. | Confirm, Cancel |
| `appointment_reminder_soon` | 2 hours before | Hello {{1}}, your appointment with {{2}} is today at {{3}}. | Confirm, Cancel |
| `appointment_cancelled_by_clinic` | The clinic closes a day (or shortens its hours) and cancels the appointments it leaves out | Hello {{1}}, we're sorry: your appointment with {{2}} on {{3}} at {{4}} is cancelled because the doctor is not available then. Tap Book another time to choose a new one. | Book another time |

  Arabic draft for `appointment_reminder`: مرحباً {{1}}، نذكّرك بموعدك مع {{2}} غداً {{3}} الساعة {{4}}.
  Buttons: تأكيد، إلغاء

  Arabic draft for `appointment_cancelled_by_clinic`: مرحباً {{1}}، نعتذر: أُلغي موعدك مع {{2}} يوم {{3}}
  الساعة {{4}} لأن الطبيب غير متاح في ذلك الوقت. اضغط «احجز موعدًا آخر» لاختيار موعد جديد.
  Button: احجز موعدًا آخر. Submitted as UTILITY; Meta decides the category on review.

- Retry transient errors (5xx, rate limits) with backoff, up to 3 attempts. After that, mark the
  reminder `FAILED` and show it under Needs attention.
- Each outbound message gets an estimated cost from `message_rate`.

## 7. Configuration (environment variables)

`DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `WA_APP_SECRET`, `WA_VERIFY_TOKEN`,
`WA_GRAPH_VERSION`, `TOKEN_ENCRYPTION_KEY`, `OPERATOR_ALERT_PHONE`, `APP_BASE_URL`.
Per-clinic WhatsApp credentials live in the database, encrypted.

## 8. Quality bar

- **Slot engine:** unit tests for daylight saving changes in `Asia/Beirut`, closed days,
  overlapping appointments and slot-length changes.
- **Reminders:** idempotency with two concurrent workers, quiet hours, and cancellation of pending
  reminders.
- **Webhook:** valid and invalid signatures, duplicate events, button-reply parsing.
- **Booking:** every state transition, session expiry, and the double-booking race (Testcontainers).
- **Operations:** a reminder goes out within 2 minutes of its due time; nightly `pg_dump` backup
  stored off the server, with one tested restore; an alert to the operator when sends start failing
  or the scheduler stalls.

## 9. Milestones

| Milestone | Delivers | 30-day plan day |
| --- | --- | --- |
| M0 Scaffold | Maven project, Docker Compose, Flyway baseline, health endpoint, CI running `./mvnw verify` | Day 4 |
| M1 Domain and slot engine | Entities, migrations, slot calculation with tests | Day 5 |
| M2 WhatsApp client and webhook | Fake and real clients, signature check, message log, dev simulator page | Day 5 |
| M3 Reminders | Reminder rows, scheduler, button replies | Day 6 |
| M4 Dashboard | Login, today and week views, manual entry, settings | Day 8 |
| M5 WhatsApp booking | Conversation state machine and interactive messages | Day 9 |
| M8 Demo and onboarding | "Dr. Demo" seed and operator onboarding page | Day 11 |
| M7 Deploy | Dockerfile, Caddy with HTTPS, VPS setup, backups, alerts | Before day 15 |
| M6 Report and costs | Monthly report, CSV export, cost tracking | Day 22 |

## 10. Open questions

- Does Meta's option to keep the WhatsApp Business app and the API on one number work for Lebanese
  numbers? Until confirmed, pilots use a dedicated new number.
- What is Meta's current utility-message rate for Lebanon? Fill `message_rate` from the rate card.
- Will Meta approve the Arabic template wording as drafted, or does it need adjusting?
