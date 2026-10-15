-- Reminders (M3). Append-only: never edit once applied.

-- SENDING: claimed by a worker and about to be sent. Never claimed again, so a crash between
-- claiming and recording the result can't send the reminder twice.
alter table reminder drop constraint reminder_status_check;
alter table reminder add constraint reminder_status_check
    check (status in ('PENDING', 'SENDING', 'SENT', 'SKIPPED', 'CANCELLED', 'FAILED'));

-- Optimistic locking: a patient's Cancel tap and a secretary's edit can't silently overwrite each other.
alter table appointment add column version integer not null default 0;
