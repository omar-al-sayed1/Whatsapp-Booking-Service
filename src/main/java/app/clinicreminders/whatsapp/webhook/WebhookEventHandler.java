package app.clinicreminders.whatsapp.webhook;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
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
 * and applies delivery statuses. A number may belong to several patients (a family): STOP opts
 * them all out, and they all get its WhatsApp user id.
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
        List<Patient> household = findHousehold(clinicId, message.phoneE164(), message.userId());
        if (message.userId() != null) {
            linkUser(clinicId, household, message.userId());
        }
        if (message.content() instanceof Text text && OptOutWords.isOptOut(text.body())) {
            optOut(clinic, household, message, text.body());
            return;
        }
        events.publishEvent(new InboundMessageReceived(clinicId, household.isEmpty() ? null : household.getFirst().getId(),
                logId.get(), message));
    }

    private void optOut(Clinic clinic, List<Patient> household, InboundMessage message, String body) {
        if (household.isEmpty()) {
            if (message.phoneE164() == null) {
                // We only ever message by phone number, and we don't have theirs.
                log.warn("Clinic {}: opt-out from a user whose number Meta didn't share", clinic.getId());
                return;
            }
            Language language = OptOutWords.isArabic(body) ? Language.AR : clinic.getDefaultLanguage();
            String name = message.profileName() != null ? message.profileName() : message.phoneE164();
            Patient patient = new Patient(clinic.getId(), name, message.phoneE164(), language);
            if (message.userId() != null) {
                patient.linkWhatsAppUser(message.userId());
            }
            household = List.of(patient);
        }
        for (Patient patient : household) {
            patient.optOut();
            patients.save(patient);
            log.info("Clinic {}: patient {} opted out", clinic.getId(), patient.getId());
        }
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
            linkUser(clinicId, patients.findByClinicIdAndPhoneE164OrderById(clinicId, update.recipientPhoneE164()),
                    update.recipientUserId());
        }
    }

    /** The patients on the sender's number or, when Meta hides it, with their WhatsApp user id. */
    private List<Patient> findHousehold(long clinicId, @Nullable String phone, @Nullable String userId) {
        List<Patient> byPhone = phone == null ? List.of() : patients.findByClinicIdAndPhoneE164OrderById(clinicId, phone);
        if (!byPhone.isEmpty() || userId == null) {
            return byPhone;
        }
        return patients.findByClinicIdAndWaUserIdOrderById(clinicId, userId);
    }

    /**
     * Gives the number's patients this id. Skipped if a patient on another number already has it,
     * rather than fail the delivery.
     */
    private void linkUser(long clinicId, List<Patient> household, String userId) {
        if (household.isEmpty() || household.stream().allMatch(patient -> userId.equals(patient.getWaUserId()))) {
            return;
        }
        String phone = household.getFirst().getPhoneE164();
        Optional<Patient> holder = patients.findByClinicIdAndWaUserIdOrderById(clinicId, userId).stream()
                .filter(patient -> !patient.getPhoneE164().equals(phone))
                .findFirst();
        if (holder.isPresent()) {
            log.warn("Clinic {}: WhatsApp user id already belongs to patient {}, not linking to the patients of another number",
                    clinicId, holder.get().getId());
            return;
        }
        household.forEach(patient -> patient.linkWhatsAppUser(userId));
    }
}
