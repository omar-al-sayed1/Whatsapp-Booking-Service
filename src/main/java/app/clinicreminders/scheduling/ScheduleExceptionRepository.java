package app.clinicreminders.scheduling;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

public interface ScheduleExceptionRepository extends Repository<ScheduleException, Long> {

    /** Exceptions with a date from {@code from} to {@code to}, both inclusive. */
    List<ScheduleException> findByClinicIdAndDoctorIdAndExceptionDateBetween(
            Long clinicId, Long doctorId, LocalDate from, LocalDate to);

    /** Today's and future exceptions, soonest first (the settings page). */
    List<ScheduleException> findByClinicIdAndDoctorIdAndExceptionDateGreaterThanEqualOrderByExceptionDate(
            Long clinicId, Long doctorId, LocalDate from);

    Optional<ScheduleException> findByClinicIdAndDoctorIdAndExceptionDate(Long clinicId, Long doctorId, LocalDate date);

    Optional<ScheduleException> findByIdAndClinicId(Long id, Long clinicId);

    List<ScheduleException> findByClinicIdAndDoctorId(Long clinicId, Long doctorId);

    ScheduleException save(ScheduleException exception);

    void delete(ScheduleException exception);
}
