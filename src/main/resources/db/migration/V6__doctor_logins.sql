-- Doctors can log in. A DOCTOR login belongs to exactly one doctor of its own clinic (the composite
-- key keeps it from pointing at another clinic's doctor), and a doctor has at most one login.
alter table staff_user drop constraint staff_user_role_check;
alter table staff_user add constraint staff_user_role_check check (role in ('OPERATOR', 'OWNER', 'SECRETARY', 'DOCTOR'));

alter table staff_user add column doctor_id bigint;
alter table staff_user add constraint staff_user_doctor_fk foreign key (doctor_id, clinic_id) references doctor (id, clinic_id);
alter table staff_user add constraint staff_user_doctor_check check ((role = 'DOCTOR') = (doctor_id is not null));
create unique index staff_user_doctor_uq on staff_user (doctor_id) where doctor_id is not null;
