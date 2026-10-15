package app.clinicreminders.whatsapp;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.common.Sleeper;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.whatsapp.OutboundMessage.ReplyButtonsMessage;
import app.clinicreminders.whatsapp.OutboundMessage.TemplateMessage;
import app.clinicreminders.whatsapp.OutboundMessage.TextMessage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Every outbound message goes through here: refuses opted-out patients, sends with the clinic's
 * credentials, retries transient failures and writes {@code message_log} with an estimated cost.
 *
 * <p>Runs without a transaction of its own so no database connection is held during HTTP calls
 * and retries. Retries block for up to 5 seconds, so call it from background work (the reminder
 * scheduler, the webhook inbox), never from a web request.
 */
@Service
public class WhatsAppMessenger {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppMessenger.class);

    /** Waits before the 2nd and 3rd attempts. */
    static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(1), Duration.ofSeconds(4));

    public enum Result { SENT, FAILED, OPTED_OUT }

    public record SendOutcome(Result result, @Nullable String waMessageId, @Nullable Long messageLogId) {
    }

    private final WhatsAppClient client;
    private final ClinicRepository clinics;
    private final PatientRepository patients;
    private final MessageLogRepository logs;
    private final MessageCostEstimator costs;
    private final TokenCipher tokenCipher;
    private final Sleeper sleeper;
    private final Clock clock;

    WhatsAppMessenger(WhatsAppClient client, ClinicRepository clinics, PatientRepository patients,
                      MessageLogRepository logs, MessageCostEstimator costs, TokenCipher tokenCipher,
                      Sleeper sleeper, Clock clock) {
        this.client = client;
        this.clinics = clinics;
        this.patients = patients;
        this.logs = logs;
        this.costs = costs;
        this.tokenCipher = tokenCipher;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    /**
     * @param appointmentId the appointment the message is about, if any (reminders, confirmations)
     */
    public SendOutcome send(long clinicId, OutboundMessage message, @Nullable Long appointmentId) {
        Clinic clinic = clinics.findById(clinicId).orElseThrow(() -> new NotFoundException("Clinic", clinicId));
        if (patients.findByClinicIdAndPhoneE164(clinicId, message.to()).filter(Patient::isOptedOut).isPresent()) {
            log.info("Not messaging an opted-out patient of clinic {}", clinicId);
            return new SendOutcome(Result.OPTED_OUT, null, null);
        }
        MessageKind kind = kindOf(message);
        String templateName = message instanceof TemplateMessage template ? template.name() : null;
        try {
            String waMessageId = sendWithRetries(account(clinic), message);
            String category = costs.categoryFor(message);
            BigDecimal cost = costs.estimate(message.to(), category, LocalDate.now(clock));
            MessageLog sent = logs.save(MessageLog.sent(clinicId, appointmentId, kind, templateName, waMessageId,
                    category, cost));
            return new SendOutcome(Result.SENT, waMessageId, sent.getId());
        } catch (WhatsAppException e) {
            log.warn("WhatsApp send for clinic {} failed: {}", clinicId, e.getMessage());
            MessageLog failed = logs.save(MessageLog.failed(clinicId, appointmentId, kind, templateName,
                    e.getCode(), e.getTitle()));
            return new SendOutcome(Result.FAILED, null, failed.getId());
        }
    }

    private String sendWithRetries(WhatsAppAccount account, OutboundMessage message) {
        for (int attempt = 1; ; attempt++) {
            try {
                return client.send(account, message);
            } catch (WhatsAppException e) {
                if (!e.isRetryable() || attempt > BACKOFF.size()) {
                    throw e;
                }
                log.info("Retrying WhatsApp send after attempt {}: {}", attempt, e.getMessage());
                sleeper.sleep(BACKOFF.get(attempt - 1));
            }
        }
    }

    private WhatsAppAccount account(Clinic clinic) {
        if (clinic.getWaPhoneNumberId() == null) {
            throw new WhatsAppException(null, "The clinic is not connected to WhatsApp", false);
        }
        byte[] encrypted = clinic.getWaAccessTokenEncrypted();
        try {
            String token = encrypted == null ? null : tokenCipher.decrypt(clinic.getId(), encrypted);
            return new WhatsAppAccount(clinic.getWaPhoneNumberId(), token);
        } catch (IllegalStateException e) {
            log.error("Clinic {}: {}", clinic.getId(), e.getMessage());
            throw new WhatsAppException(null, "The clinic's access token can't be decrypted", false);
        }
    }

    private static MessageKind kindOf(OutboundMessage message) {
        return switch (message) {
            case TemplateMessage template -> MessageKind.TEMPLATE;
            case TextMessage text -> MessageKind.TEXT;
            case ReplyButtonsMessage buttons -> MessageKind.INTERACTIVE;
        };
    }
}
