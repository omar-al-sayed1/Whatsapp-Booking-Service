-- Monthly report (M6). Append-only: never edit once applied.

-- When the patient confirmed (a reminder's Confirm). The status moves on to ARRIVED or NO_SHOW, so
-- the report needs this to count confirmations; moving an appointment clears it.
alter table appointment add column confirmed_at timestamptz;
