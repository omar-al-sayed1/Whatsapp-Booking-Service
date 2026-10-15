package app.clinicreminders.scheduling;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.repository.Repository;

public interface ScheduleExceptionRepository extends Repository<ScheduleException, Long> {

    /** Exceptions with a date from {@code from} to {@code to}, both inclusive. */
    List<ScheduleException> findByClinicIdAndDoctorIdAndExceptionDateBetween(
            Long clinicId, Long doctorId, LocalDate from, LocalDate to);

    ScheduleException save(ScheduleException exception);
}
