package app.clinicreminders.scheduling;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Replaces a doctor's weekly hours on one date: either closed, or open with different hours. */
@Entity
@Table(name = "schedule_exception")
public class ScheduleException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id")
    private Long clinicId;

    @Column(name = "doctor_id")
    private Long doctorId;

    @Column(name = "exception_date")
    private LocalDate exceptionDate;

    private boolean closed;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    protected ScheduleException() {
    }

    private ScheduleException(long clinicId, long doctorId, LocalDate date, TimeWindow hours) {
        this.clinicId = clinicId;
        this.doctorId = doctorId;
        this.exceptionDate = date;
        this.closed = hours == null;
        this.startTime = hours == null ? null : hours.start();
        this.endTime = hours == null ? null : hours.end();
    }

    public static ScheduleException closed(long clinicId, long doctorId, LocalDate date) {
        return new ScheduleException(clinicId, doctorId, date, null);
    }

    public static ScheduleException withHours(long clinicId, long doctorId, LocalDate date, TimeWindow hours) {
        return new ScheduleException(clinicId, doctorId, date, hours);
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

    public LocalDate getExceptionDate() {
        return exceptionDate;
    }

    public boolean isClosed() {
        return closed;
    }

    /** The replacement hours, or empty when the doctor is closed that day. */
    public Optional<TimeWindow> hours() {
        return closed ? Optional.empty() : Optional.of(new TimeWindow(startTime, endTime));
    }
}
