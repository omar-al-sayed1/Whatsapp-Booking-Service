-- The appointment length is set per clinic by the operator (20 minutes unless changed), and every
-- doctor of the clinic follows it. doctor.slot_minutes stays: the slot engine reads it per doctor,
-- and appointments keep the start and end they were booked with.
alter table clinic add column slot_minutes integer not null default 20 check (slot_minutes between 5 and 240);

-- No clinic is live yet: bring every doctor in line with the new default.
update doctor set slot_minutes = 20;
