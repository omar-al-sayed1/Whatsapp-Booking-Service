package app.clinicreminders.scheduling;

import java.time.DayOfWeek;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Weekly opening hours: one window on one weekday. A day can have several (split shifts). */
@Entity
@Table(name = "schedule_rule")
public class ScheduleRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id")
    private Long clinicId;

    @Column(name = "doctor_id")
    private Long doctorId;

    @Convert(converter = DayOfWeekConverter.class)
    @Column(name = "day_of_week")
    private DayOfWeek dayOfWeek;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    protected ScheduleRule() {
    }

    public ScheduleRule(long clinicId, long doctorId, DayOfWeek dayOfWeek, TimeWindow window) {
        this.clinicId = clinicId;
        this.doctorId = doctorId;
        this.dayOfWeek = dayOfWeek;
        this.startTime = window.start();
        this.endTime = window.end();
    }

    public Long getId() {
        return id;
    }

    public Long getClinicId() {
        return clinicId;
    }

    public Long getDoctorId() {
        return doctorId;
    }

    public DayOfWeek getDayOfWeek() {
        return dayOfWeek;
    }

    public TimeWindow window() {
        return new TimeWindow(startTime, endTime);
    }
}
