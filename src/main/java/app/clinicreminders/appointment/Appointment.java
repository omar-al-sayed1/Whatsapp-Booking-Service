package app.clinicreminders.appointment;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;

/** Start and end are stored per appointment, so changing a doctor's slot length never moves it. */
@Entity
@Table(name = "appointment")
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id")
    private Long clinicId;

    @Column(name = "doctor_id")
    private Long doctorId;

    /** Null once the patient was deleted on request; the row stays for the monthly report. */
    @Column(name = "patient_id")
    private Long patientId;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    private AppointmentStatus status;

    @Enumerated(EnumType.STRING)
    private AppointmentSource source;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Generated
    @Column(name = "created_at")
    private Instant createdAt;

    protected Appointment() {
    }

    public Appointment(long clinicId, long doctorId, long patientId, Instant startsAt, Instant endsAt,
                       AppointmentSource source) {
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("startsAt must be before endsAt");
        }
        this.clinicId = clinicId;
        this.doctorId = doctorId;
        this.patientId = patientId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.source = source;
        this.status = AppointmentStatus.BOOKED;
    }

    public void cancel(Instant now) {
        this.status = AppointmentStatus.CANCELLED;
        this.cancelledAt = now;
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

    public Long getPatientId() {
        return patientId;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public AppointmentStatus getStatus() {
        return status;
    }

    public AppointmentSource getSource() {
        return source;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
