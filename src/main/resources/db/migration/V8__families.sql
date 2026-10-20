-- Families. Append-only: never edit once applied.

-- A parent books for their children from one phone, so several patients of a clinic may share a
-- number, each under their own name.
alter table patient drop constraint patient_clinic_id_phone_e164_key;
create unique index patient_phone_name_uq on patient (clinic_id, phone_e164, lower(name));

-- They also share the number's WhatsApp user id.
drop index patient_wa_user_id_uq;
create index patient_wa_user_id_idx on patient (clinic_id, wa_user_id) where wa_user_id is not null;
