package app.clinicreminders.patient;

import java.util.Optional;

import org.springframework.data.repository.Repository;

public interface PatientRepository extends Repository<Patient, Long> {

    Optional<Patient> findByClinicIdAndPhoneE164(Long clinicId, String phoneE164);

    Optional<Patient> findByClinicIdAndWaUserId(Long clinicId, String waUserId);

    Patient save(Patient patient);
}
