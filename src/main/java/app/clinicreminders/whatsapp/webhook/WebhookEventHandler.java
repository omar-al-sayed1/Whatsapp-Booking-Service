package app.clinicreminders.whatsapp.webhook;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.whatsapp.MessageCostEstimator;
import app.clinicreminders.whatsapp.MessageLogRepository;
import app.clinicreminders.whatsapp.MessageStatus;
import app.clinicreminders.whatsapp.webhook.InboundContent.Text;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one webhook delivery inside the inbox processor's transaction: routes each change to its
 * clinic, logs and dedupes inbound messages, handles STOP, publishes {@link InboundMessageReceived}
 * and applies delivery statuses.
 */
@Component
public class WebhookEventHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventHandler.class);

    private final WebhookPayloadParser parser;
    private final ClinicRepository clinics;
    private final PatientRepository patients;
    private final MessageLogRepository logs;
    private final MessageCostEstimator costs;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    WebhookEventHandler(WebhookPayloadParser parser, ClinicRepository clinics, PatientRepository patients,
                        MessageLogRepository logs, MessageCostEstimator costs, ApplicationEventPublisher events,
                        Clock clock) {
        this.parser = parser;
        this.clinics = clinics;
        this.patients = patients;
        this.logs = logs;
        this.costs = costs;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(String payload) {
        for (WebhookChange change : parser.parse(payload)) {
            Optional<Clinic> clinic = clinics.findByWaPhoneNumberId(change.phoneNumberId());
            if (clinic.isEmpty()) {
                log.warn("Ignoring webhook for unknown WhatsApp phone number id {}", change.phoneNumberId());
                continue;
            }
            change.messages().forEach(message -> onMessage(clinic.get(), message));
            change.statuses().forEach(status -> onStatus(clinic.get().getId(), status));
        }
    }

    private void onMessage(Clinic clinic, InboundMessage message) {
        long clinicId = clinic.getId();
        Optional<Long> logId = logs.insertInboundIfNew(clinicId, message.waMessageId(), message.content().kind().name());
        if (logId.isEmpty()) {
            log.debug("Duplicate delivery of {} ignored", message.waMessageId());
            return;
        }
        Patient patient = findPatient(clinicId, message.phoneE164(), message.userId()).orElse(null);
        if (patient != null && message.userId() != null) {
            linkUser(clinicId, patient, message.userId());
        }
        if (message.content() instanceof Text text && OptOutWords.isOptOut(text.body())) {
            optOut(clinic, patient, message, text.body());
            return;
        }
        events.publishEvent(new InboundMessageReceived(clinicId, patient == null ? null : patient.getId(),
                logId.get(), message));
    }

    private void optOut(Clinic clinic, @Nullable Patient patient, InboundMessage message, String body) {
        if (patient == null) {
            if (message.phoneE164() == null) {
                // We only ever message by phone number, and we don't have theirs.
                log.warn("Clinic {}: opt-out from a user whose number Meta didn't share", clinic.getId());
                return;
            }
            Language language = OptOutWords.isArabic(body) ? Language.AR : clinic.getDefaultLanguage();
            String name = message.profileName() != null ? message.profileName() : message.phoneE164();
            patient = new Patient(clinic.getId(), name, message.phoneE164(), language);
            if (message.userId() != null) {
                patient.linkWhatsAppUser(message.userId());
            }
        }
        patient.optOut();
        patients.save(patient);
        log.info("Clinic {}: patient {} opted out", clinic.getId(), patient.getId());
    }

    private void onStatus(long clinicId, StatusUpdate update) {
        MessageStatus status = MessageStatus.fromWebhook(update.status());
        if (status == null) {
            return;
        }
        logs.findByClinicIdAndWaMessageId(clinicId, update.waMessageId()).ifPresentOrElse(
                message -> {
                    message.applyStatus(status, update.errorCode(), update.errorTitle());
                    if (update.pricingCategory() != null) {
                        message.reprice(update.pricingCategory(), costs.reprice(update.recipientPhoneE164(),
                                update.pricingCategory(), update.pricingType(), message.getEstCostUsd(),
                                LocalDate.now(clock)));
                    }
                },
                () -> log.debug("Status for unknown message {} ignored", update.waMessageId()));
        if (update.recipientPhoneE164() != null && update.recipientUserId() != null) {
            patients.findByClinicIdAndPhoneE164(clinicId, update.recipientPhoneE164())
                    .ifPresent(patient -> linkUser(clinicId, patient, update.recipientUserId()));
        }
    }

    private Optional<Patient> findPatient(long clinicId, @Nullable String phone, @Nullable String userId) {
        Optional<Patient> byPhone = phone == null ? Optional.empty() : patients.findByClinicIdAndPhoneE164(clinicId, phone);
        if (byPhone.isPresent() || userId == null) {
            return byPhone;
        }
        return patients.findByClinicIdAndWaUserId(clinicId, userId);
    }

    /** Skips the link if another patient of the clinic already has this id, rather than fail the delivery. */
    private void linkUser(long clinicId, Patient patient, String userId) {
        if (userId.equals(patient.getWaUserId())) {
            return;
        }
        Optional<Patient> holder = patients.findByClinicIdAndWaUserId(clinicId, userId);
        if (holder.isPresent() && !holder.get().getId().equals(patient.getId())) {
            log.warn("Clinic {}: WhatsApp user id already belongs to patient {}, not linking to {}",
                    clinicId, holder.get().getId(), patient.getId());
            return;
        }
        patient.linkWhatsAppUser(userId);
    }
}
