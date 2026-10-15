package app.clinicreminders.clinic;

import java.util.Optional;

import org.springframework.data.repository.Repository;

/** The clinic is the tenant itself, so its id is the tenant filter. */
public interface ClinicRepository extends Repository<Clinic, Long> {

    Optional<Clinic> findById(Long id);

    Clinic save(Clinic clinic);
}
