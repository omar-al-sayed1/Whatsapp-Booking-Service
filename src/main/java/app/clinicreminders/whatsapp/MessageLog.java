package app.clinicreminders.whatsapp;

import java.math.BigDecimal;
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

/**
 * One row per WhatsApp message in or out. Holds no message body and no phone number, so deleting
 * a patient leaves nothing identifying here. Mapping only; behaviour arrives in M2.
 */
@Entity
@Table(name = "message_log")
public class MessageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id")
    private Long clinicId;

    @Column(name = "appointment_id")
    private Long appointmentId;

    @Enumerated(EnumType.STRING)
    private MessageDirection direction;

    /** Unique; the webhook dedupes on it. */
    @Column(name = "wa_message_id")
    private String waMessageId;

    @Enumerated(EnumType.STRING)
    private MessageKind kind;

    @Column(name = "template_name")
    private String templateName;

    @Column(name = "pricing_category")
    private String pricingCategory;

    @Enumerated(EnumType.STRING)
    private MessageStatus status;

    @Column(name = "est_cost_usd")
    private BigDecimal estCostUsd;

    @Generated
    @Column(name = "created_at")
    private Instant createdAt;

    protected MessageLog() {
    }

    public MessageLog(long clinicId, Long appointmentId, MessageDirection direction, MessageKind kind,
                      String waMessageId) {
        this.clinicId = clinicId;
        this.appointmentId = appointmentId;
        this.direction = direction;
        this.kind = kind;
        this.waMessageId = waMessageId;
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

    public MessageDirection getDirection() {
        return direction;
    }

    public String getWaMessageId() {
        return waMessageId;
    }

    public MessageKind getKind() {
        return kind;
    }

    public String getTemplateName() {
        return templateName;
    }

    public String getPricingCategory() {
        return pricingCategory;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public BigDecimal getEstCostUsd() {
        return estCostUsd;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
