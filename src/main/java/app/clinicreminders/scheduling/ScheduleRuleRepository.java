package app.clinicreminders.scheduling;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

public interface ScheduleRuleRepository extends Repository<ScheduleRule, Long> {

    List<ScheduleRule> findByClinicIdAndDoctorId(Long clinicId, Long doctorId);

    Optional<ScheduleRule> findByIdAndClinicId(Long id, Long clinicId);

    ScheduleRule save(ScheduleRule rule);

    void delete(ScheduleRule rule);
}
