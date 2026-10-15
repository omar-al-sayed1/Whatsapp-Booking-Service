package app.clinicreminders.scheduling;

import java.util.Optional;

import org.springframework.data.repository.Repository;

public interface DoctorRepository extends Repository<Doctor, Long> {

    Optional<Doctor> findByIdAndClinicId(Long id, Long clinicId);

    Doctor save(Doctor doctor);
}
