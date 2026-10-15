package app.clinicreminders.reminder;

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

@Entity
@Table(name = "reminder")
public class Reminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id")
    private Long clinicId;

    @Column(name = "appointment_id")
    private Long appointmentId;

    @Enumerated(EnumType.STRING)
    private ReminderKind kind;

    @Column(name = "due_at")
    private Instant dueAt;

    @Enumerated(EnumType.STRING)
    private ReminderStatus status;

    /** Times a worker claimed it; with SENDING never re-claimed, this is 0 or 1. */
    private int attempts;

    @Column(name = "sent_message_id")
    private String sentMessageId;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Generated
    @Column(name = "created_at")
    private Instant createdAt;

    protected Reminder() {
    }

    public Reminder(long clinicId, long appointmentId, ReminderKind kind, Instant dueAt, ReminderStatus status) {
        this.clinicId = clinicId;
        this.appointmentId = appointmentId;
        this.kind = kind;
        this.dueAt = dueAt;
        this.status = status;
    }

    void markSent(String waMessageId, Instant at) {
        this.status = ReminderStatus.SENT;
        this.sentMessageId = waMessageId;
        this.sentAt = at;
    }

    void finish(ReminderStatus outcome) {
        this.status = outcome;
    }

    public Long getId() {
        return id;
    }

    public Long getClinicId() {
        return clinicId;
    }

    public Long getAppointmentId() {
        return appointmentId;
    }

    public ReminderKind getKind() {
        return kind;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public ReminderStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getSentMessageId() {
        return sentMessageId;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
