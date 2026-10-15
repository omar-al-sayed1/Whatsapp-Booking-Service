package app.clinicreminders.booking;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * One live booking conversation per clinic and phone number. Keyed by phone rather than patient,
 * because a conversation can start before the patient exists. Mapping only; the flow arrives in M5.
 */
@Entity
@Table(name = "conversation")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id")
    private Long clinicId;

    @Column(name = "patient_phone")
    private String patientPhone;

    @Enumerated(EnumType.STRING)
    private ConversationState state;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_json")
    private String contextJson = "{}";

    @Column(name = "needs_human")
    private boolean needsHuman;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Conversation() {
    }

    public Conversation(long clinicId, String patientPhone, ConversationState state, Instant expiresAt) {
        this.clinicId = clinicId;
        this.patientPhone = patientPhone;
        this.state = state;
        this.expiresAt = expiresAt;
    }

    /** Flags the conversation for the secretary (M5: unrecognised text). */
    public void flagForHuman() {
        this.needsHuman = true;
    }

    /** The secretary has dealt with it (e.g. called the patient back). */
    public void markHandled() {
        this.needsHuman = false;
    }

    public Long getId() {
        return id;
    }

    public Long getClinicId() {
        return clinicId;
    }

    public String getPatientPhone() {
        return patientPhone;
    }

    public ConversationState getState() {
        return state;
    }

    public String getContextJson() {
        return contextJson;
    }

    public boolean isNeedsHuman() {
        return needsHuman;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
