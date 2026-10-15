-- Dashboard (M4). Append-only: never edit once applied.

-- Who cancelled, so the dashboard can flag patient cancellations (slots to refill).
alter table appointment add column cancelled_by text
    check (cancelled_by in ('PATIENT', 'STAFF'));
alter table appointment add constraint appointment_cancelled_by_status
    check (cancelled_by is null or status = 'CANCELLED');
