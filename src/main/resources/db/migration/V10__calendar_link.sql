-- "Add to my calendar" links. Append-only: never edit once applied.

-- A random, unguessable key for each appointment's public calendar page: the link carries no
-- appointment id and nothing about the patient.
alter table appointment add column calendar_token uuid not null default gen_random_uuid();
create unique index appointment_calendar_token_uq on appointment (calendar_token);
