-- WhatsApp webhooks and sending (M2). Append-only: never edit once applied.

-- Verified webhook deliveries, stored before we answer 200 so a crash or restart can't lose them.
-- Holds phone numbers and message text, so each row is deleted as soon as it has been processed.
-- Not tenant-owned: one delivery can carry several clinics' numbers; the clinic is resolved per change.
create table webhook_inbox (
    id          bigint generated always as identity primary key,
    received_at timestamptz not null default now(),
    payload     text        not null,   -- raw signed body; text, not jsonb (jsonb rejects \u0000)
    attempts    integer     not null default 0,
    last_error  text,
    failed      boolean     not null default false
);
create index webhook_inbox_pending_idx on webhook_inbox (id) where not failed;

-- Inbound images, voice notes, locations etc. are logged as OTHER.
alter table message_log drop constraint message_log_kind_check;
alter table message_log add constraint message_log_kind_check
    check (kind in ('TEMPLATE', 'INTERACTIVE', 'TEXT', 'BUTTON_REPLY', 'OTHER'));

-- Meta's error for failed sends (Needs attention, debugging). Never message text.
alter table message_log add column error_code  integer;
alter table message_log add column error_title text;

-- Meta's business-scoped user id (BSUID): the fallback when a webhook omits the phone number.
alter table patient add column wa_user_id text;
create unique index patient_wa_user_id_uq on patient (clinic_id, wa_user_id) where wa_user_id is not null;
