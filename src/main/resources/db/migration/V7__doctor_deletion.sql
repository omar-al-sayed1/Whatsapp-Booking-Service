-- A deleted doctor leaves the clinic (lists, booking, their hours and login) but the row stays, so
-- their past appointments keep their history. Deleting is refused while appointments are still to come.
alter table doctor add column deleted_at timestamptz;
