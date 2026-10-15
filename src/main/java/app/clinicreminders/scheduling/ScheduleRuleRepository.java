package app.clinicreminders.scheduling;

import java.util.List;

import org.springframework.data.repository.Repository;

public interface ScheduleRuleRepository extends Repository<ScheduleRule, Long> {

    List<ScheduleRule> findByClinicIdAndDoctorId(Long clinicId, Long doctorId);

    ScheduleRule save(ScheduleRule rule);
}
