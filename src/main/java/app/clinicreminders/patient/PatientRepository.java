package app.clinicreminders.patient;

import org.springframework.data.repository.Repository;

public interface PatientRepository extends Repository<Patient, Long> {

    Patient save(Patient patient);
}
