package app.clinicreminders.scheduling;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

public interface DoctorRepository extends Repository<Doctor, Long> {

    Optional<Doctor> findByIdAndClinicId(Long id, Long clinicId);

    /** Active and inactive, by name. */
    List<Doctor> findByClinicIdOrderByDisplayName(Long clinicId);

    Doctor save(Doctor doctor);
}
