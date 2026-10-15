-- Clinic Reminders data model (docs/SPEC.md section 4). Append-only: never edit once applied.
-- * Tenant-owned tables carry clinic_id. Children reference parents by (id, clinic_id),
--   so the database itself rejects a row that points at another clinic's data.
-- * Instants are timestamptz (UTC). Wall-clock times (opening hours, quiet hours) are `time`,
--   interpreted in clinic.timezone.
-- * Enums are text + CHECK: easier to extend with append-only migrations than Postgres enum types.

create table clinic (
    id                        bigint generated always as identity primary key,
    name                      text        not null,
    timezone                  text        not null default 'Asia/Beirut',
    default_language          text        not null check (default_language in ('ar', 'en')),
    address                   text,
    hours_text                text,
    wa_phone_number_id        text        unique,
    wa_business_account_id    text,
    wa_access_token_encrypted bytea,                  -- AES-GCM: nonce || ciphertext || tag
    quiet_hours_start         time        not null default '21:00',
    quiet_hours_end           time        not null default '08:00',
    reminder_h24_enabled      boolean     not null default true,
    reminder_h2_enabled       boolean     not null default true,
    created_at                timestamptz not null default now()
);

create table staff_user (
    id            bigint generated always as identity primary key,
    clinic_id     bigint      references clinic (id),
    username      text        not null,
    password_hash text        not null,
    role          text        not null check (role in ('OPERATOR', 'OWNER', 'SECRETARY')),
    language      text        not null check (language in ('ar', 'en')),
    enabled       boolean     not null default true,
    created_at    timestamptz not null default now(),
    check ((role = 'OPERATOR') = (clinic_id is null))
);
create unique index staff_user_username_uq on staff_user (lower(username));
create index staff_user_clinic_idx on staff_user (clinic_id);

create table doctor (
    id           bigint generated always as identity primary key,
    clinic_id    bigint      not null references clinic (id),
    display_name text        not null,
    slot_minutes integer     not null check (slot_minutes between 5 and 240),
    active       boolean     not null default true,
    created_at   timestamptz not null default now(),
    unique (id, clinic_id)
);
create index doctor_clinic_idx on doctor (clinic_id);

create table schedule_rule (
    id          bigint generated always as identity primary key,
    clinic_id   bigint   not null,
    doctor_id   bigint   not null,
    day_of_week smallint not null check (day_of_week between 1 and 7),   -- ISO-8601, 1 = Monday
    start_time  time     not null,
    end_time    time     not null,
    check (start_time < end_time),
    foreign key (doctor_id, clinic_id) references doctor (id, clinic_id) on delete cascade
);
create index schedule_rule_doctor_idx on schedule_rule (doctor_id, clinic_id);

create table schedule_exception (
    id             bigint generated always as identity primary key,
    clinic_id      bigint  not null,
    doctor_id      bigint  not null,
    exception_date date    not null,
    closed         boolean not null,
    start_time     time,
    end_time       time,
    check ((closed and start_time is null and end_time is null)
        or (not closed and start_time is not null and end_time is not null and start_time < end_time)),
    unique (doctor_id, exception_date),
    foreign key (doctor_id, clinic_id) references doctor (id, clinic_id) on delete cascade
);

create table patient (
    id         bigint generated always as identity primary key,
    clinic_id  bigint      not null references clinic (id),
    name       text        not null,
    phone_e164 text        not null check (phone_e164 ~ '^\+[1-9][0-9]{6,14}$'),
    language   text        not null check (language in ('ar', 'en')),
    opted_out  boolean     not null default false,
    created_at timestamptz not null default now(),
    unique (clinic_id, phone_e164),
    unique (id, clinic_id)
);

create table appointment (
    id           bigint generated always as identity primary key,
    clinic_id    bigint      not null,
    doctor_id    bigint      not null,
    patient_id   bigint,                         -- null once the patient is deleted on request
    starts_at    timestamptz not null,
    ends_at      timestamptz not null,
    status       text        not null check (status in ('BOOKED', 'CONFIRMED', 'CANCELLED', 'NO_SHOW', 'ARRIVED')),
    source       text        not null check (source in ('WHATSAPP', 'MANUAL')),
    cancelled_at timestamptz,
    created_at   timestamptz not null default now(),
    check (ends_at > starts_at),
    check ((status = 'CANCELLED') = (cancelled_at is not null)),
    unique (id, clinic_id),
    foreign key (doctor_id, clinic_id) references doctor (id, clinic_id),
    foreign key (patient_id, clinic_id) references patient (id, clinic_id) on delete set null (patient_id)
);
-- SPEC section 4: no two active appointments for the same doctor and start time.
-- Also the final guard against the WhatsApp double-booking race (section 5.2).
create unique index appointment_doctor_start_active_uq on appointment (doctor_id, starts_at)
    where status <> 'CANCELLED';
create index appointment_clinic_starts_idx on appointment (clinic_id, starts_at);
create index appointment_patient_idx on appointment (patient_id);

create table reminder (
    id              bigint generated always as identity primary key,
    clinic_id       bigint      not null,
    appointment_id  bigint      not null,
    kind            text        not null check (kind in ('H24', 'H2')),
    due_at          timestamptz not null,
    status          text        not null check (status in ('PENDING', 'SENT', 'SKIPPED', 'CANCELLED', 'FAILED')),
    attempts        integer     not null default 0,
    sent_message_id text,
    sent_at         timestamptz,
    created_at      timestamptz not null default now(),
    foreign key (appointment_id, clinic_id) references appointment (id, clinic_id) on delete cascade
);
create unique index reminder_pending_uq on reminder (appointment_id, kind) where status = 'PENDING';
create index reminder_due_idx on reminder (due_at) where status = 'PENDING';
create index reminder_appointment_idx on reminder (appointment_id);

create table message_log (
    id               bigint generated always as identity primary key,
    clinic_id        bigint      not null references clinic (id),
    appointment_id   bigint,
    direction        text        not null check (direction in ('IN', 'OUT')),
    wa_message_id    text        unique,         -- dedupe key; null if a send failed before Meta assigned one
    kind             text        not null check (kind in ('TEMPLATE', 'INTERACTIVE', 'TEXT', 'BUTTON_REPLY')),
    template_name    text,
    pricing_category text,
    status           text        check (status in ('SENT', 'DELIVERED', 'READ', 'FAILED')),
    est_cost_usd     numeric(10, 6),
    created_at       timestamptz not null default now(),
    foreign key (appointment_id, clinic_id) references appointment (id, clinic_id) on delete set null (appointment_id)
);
create index message_log_clinic_created_idx on message_log (clinic_id, created_at);
create index message_log_appointment_idx on message_log (appointment_id);

create table conversation (
    id            bigint generated always as identity primary key,
    clinic_id     bigint      not null references clinic (id),
    patient_phone text        not null check (patient_phone ~ '^\+[1-9][0-9]{6,14}$'),
    state         text        not null,          -- validated in code; the state machine is M5
    context_json  jsonb       not null default '{}',
    needs_human   boolean     not null default false,
    expires_at    timestamptz not null,
    updated_at    timestamptz not null default now(),
    unique (clinic_id, patient_phone)
);
create index conversation_needs_human_idx on conversation (clinic_id) where needs_human;

-- Global reference data from Meta's rate card; not tenant-owned.
create table message_rate (
    id               bigint generated always as identity primary key,
    market           text           not null,
    pricing_category text           not null,
    usd_per_message  numeric(10, 6) not null check (usd_per_message >= 0),
    valid_from       date           not null,
    unique (market, pricing_category, valid_from)
);
