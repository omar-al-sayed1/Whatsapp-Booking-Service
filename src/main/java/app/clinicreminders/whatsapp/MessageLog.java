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
import org.jspecify.annotations.Nullable;

/**
 * One row per WhatsApp message in or out. Holds no message body and no phone number, so deleting
 * a patient leaves nothing identifying here. Inbound rows are inserted by
 * {@link MessageLogRepository#insertInboundIfNew}, which also dedupes webhook deliveries.
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

    /** Unique; the webhook dedupes on it. Null when a send failed before Meta assigned one. */
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

    @Column(name = "error_code")
    private Integer errorCode;

    @Column(name = "error_title")
    private String errorTitle;

    @Generated
    @Column(name = "created_at")
    private Instant createdAt;

    protected MessageLog() {
    }

    private MessageLog(long clinicId, @Nullable Long appointmentId, MessageKind kind, @Nullable String templateName) {
        this.clinicId = clinicId;
        this.appointmentId = appointmentId;
        this.direction = MessageDirection.OUT;
        this.kind = kind;
        this.templateName = templateName;
    }

    public static MessageLog sent(long clinicId, @Nullable Long appointmentId, MessageKind kind,
                                  @Nullable String templateName, String waMessageId,
                                  @Nullable String pricingCategory, @Nullable BigDecimal estCostUsd) {
        MessageLog log = new MessageLog(clinicId, appointmentId, kind, templateName);
        log.waMessageId = waMessageId;
        log.status = MessageStatus.SENT;
        log.pricingCategory = pricingCategory;
        log.estCostUsd = estCostUsd;
        return log;
    }

    public static MessageLog failed(long clinicId, @Nullable Long appointmentId, MessageKind kind,
                                    @Nullable String templateName, @Nullable Integer errorCode, String errorTitle) {
        MessageLog log = new MessageLog(clinicId, appointmentId, kind, templateName);
        log.status = MessageStatus.FAILED;
        log.errorCode = errorCode;
        log.errorTitle = errorTitle;
        return log;
    }

    /** Applies a status webhook. Ignores statuses that would move the message backwards. */
    public void applyStatus(MessageStatus newStatus, @Nullable Integer errorCode, @Nullable String errorTitle) {
        if (status != null && !newStatus.isAfter(status)) {
            return;
        }
        status = newStatus;
        if (newStatus == MessageStatus.FAILED) {
            this.errorCode = errorCode;
            this.errorTitle = errorTitle;
        }
    }

    /** Meta's own pricing for the message, from a status webhook. */
    public void reprice(String pricingCategory, @Nullable BigDecimal estCostUsd) {
        this.pricingCategory = pricingCategory;
        this.estCostUsd = estCostUsd;
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

    public Integer getErrorCode() {
        return errorCode;
    }

    public String getErrorTitle() {
        return errorTitle;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
