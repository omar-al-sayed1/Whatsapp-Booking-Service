package app.clinicreminders.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.whatsapp.OutboundMessage.TemplateMessage;
import app.clinicreminders.whatsapp.OutboundMessage.TextMessage;
import app.clinicreminders.whatsapp.WhatsAppMessenger.Result;
import app.clinicreminders.whatsapp.WhatsAppMessenger.SendOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WhatsAppMessengerIT extends IntegrationTest {

    private static final String PHONE = "+96170123456";
    private static final WhatsAppException RATE_LIMITED = WhatsAppException.fromMetaError(130429, "Rate limit hit");

    @Autowired
    WhatsAppMessenger messenger;

    @Autowired
    MessageLogRepository logs;

    @Autowired
    PatientRepository patients;

    @Autowired
    ClinicRepository clinics;

    @Autowired
    TokenCipher tokenCipher;

    private Clinic clinic;

    @BeforeEach
    void connectedClinicWithRates() {
        clinic = data.connectedClinic("A", "pn-a");
        data.rate("Rest of Middle East", "service", "0.0080", LocalDate.of(2026, 10, 1));
        data.rate("Rest of Middle East", "utility", "0.0110", LocalDate.of(2026, 10, 1));
    }

    @Test
    void sendsThroughTheClinicsNumberAndLogsTheEstimatedCost() {
        SendOutcome outcome = messenger.send(clinic.getId(), new TextMessage(PHONE, "hello"), null);

        assertThat(outcome.result()).isEqualTo(Result.SENT);
        assertThat(whatsApp.recent()).singleElement().satisfies(sent -> {
            assertThat(sent.phoneNumberId()).isEqualTo("pn-a");
            assertThat(sent.waMessageId()).isEqualTo(outcome.waMessageId());
        });
        MessageLog log = logs.findByClinicIdAndWaMessageId(clinic.getId(), outcome.waMessageId()).orElseThrow();
        assertThat(log.getId()).isEqualTo(outcome.messageLogId());
        assertThat(log.getDirection()).isEqualTo(MessageDirection.OUT);
        assertThat(log.getKind()).isEqualTo(MessageKind.TEXT);
        assertThat(log.getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(log.getPricingCategory()).isEqualTo("service");
        assertThat(log.getEstCostUsd()).isEqualByComparingTo("0.0080");
    }

    @Test
    void templatesAreLoggedWithTheirNameCategoryAndAppointment() {
        Appointment appointment = data.appointment(data.doctor(clinic, 30), data.patient(clinic, PHONE),
                Instant.parse("2026-11-02T07:00:00Z"), Duration.ofMinutes(30));

        SendOutcome outcome = messenger.send(clinic.getId(),
                new TemplateMessage(PHONE, "appointment_reminder", "ar", List.of("Rana", "د. خوري", "الاثنين", "09:00")),
                appointment.getId());

        MessageLog log = logs.findByClinicIdAndWaMessageId(clinic.getId(), outcome.waMessageId()).orElseThrow();
        assertThat(log.getKind()).isEqualTo(MessageKind.TEMPLATE);
        assertThat(log.getTemplateName()).isEqualTo("appointment_reminder");
        assertThat(log.getAppointmentId()).isEqualTo(appointment.getId());
        assertThat(log.getPricingCategory()).isEqualTo("utility");
        assertThat(log.getEstCostUsd()).isEqualByComparingTo("0.0110");
    }

    @Test
    void numbersOutsideAMappedMarketGetNoEstimate() {
        SendOutcome outcome = messenger.send(clinic.getId(), new TextMessage("+33612345678", "bonjour"), null);

        assertThat(logs.findByClinicIdAndWaMessageId(clinic.getId(), outcome.waMessageId()).orElseThrow().getEstCostUsd())
                .isNull();
    }

    @Test
    void neverMessagesAnOptedOutPatient() {
        Patient patient = data.patient(clinic, PHONE);
        patient.optOut();
        patients.save(patient);

        SendOutcome outcome = messenger.send(clinic.getId(), new TextMessage(PHONE, "hello"), null);

        assertThat(outcome.result()).isEqualTo(Result.OPTED_OUT);
        assertThat(whatsApp.recent()).isEmpty();
        assertThat(messageLogCount()).isZero();
    }

    @Test
    void anOptOutInAnotherClinicDoesNotApply() {
        Clinic other = data.clinic("B");
        Patient elsewhere = data.patient(other, PHONE);
        elsewhere.optOut();
        patients.save(elsewhere);

        assertThat(messenger.send(clinic.getId(), new TextMessage(PHONE, "hello"), null).result()).isEqualTo(Result.SENT);
    }

    @Test
    void retriesRateLimitsWithBackoff() {
        whatsApp.failNext(RATE_LIMITED, RATE_LIMITED);

        SendOutcome outcome = messenger.send(clinic.getId(), new TextMessage(PHONE, "hello"), null);

        assertThat(outcome.result()).isEqualTo(Result.SENT);
        assertThat(sleeper.sleeps()).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(4));
        assertThat(messageLogCount()).isEqualTo(1);
    }

    @Test
    void givesUpAfterThreeAttemptsAndLogsTheFailure() {
        whatsApp.failNext(RATE_LIMITED, RATE_LIMITED, RATE_LIMITED);

        SendOutcome outcome = messenger.send(clinic.getId(), new TextMessage(PHONE, "hello"), null);

        assertThat(outcome.result()).isEqualTo(Result.FAILED);
        assertThat(sleeper.sleeps()).hasSize(2);
        assertThat(whatsApp.recent()).isEmpty();
        Integer code = jdbc.queryForObject("select error_code from message_log where id = ?", Integer.class,
                outcome.messageLogId());
        assertThat(code).isEqualTo(130429);
    }

    @Test
    void doesNotRetryPermanentErrors() {
        whatsApp.failNext(WhatsAppException.fromMetaError(131026, "Message undeliverable"));

        SendOutcome outcome = messenger.send(clinic.getId(), new TextMessage(PHONE, "hello"), null);

        assertThat(outcome.result()).isEqualTo(Result.FAILED);
        assertThat(sleeper.sleeps()).isEmpty();
    }

    @Test
    void aClinicWithoutWhatsAppCannotSend() {
        Clinic unconnected = data.clinic("Not connected");

        SendOutcome outcome = messenger.send(unconnected.getId(), new TextMessage(PHONE, "hello"), null);

        assertThat(outcome.result()).isEqualTo(Result.FAILED);
        assertThat(errorTitle(outcome)).isEqualTo("The clinic is not connected to WhatsApp");
    }

    @Test
    void aTokenEncryptedForAnotherClinicIsNeverUsed() {
        Clinic other = data.clinic("B");
        other.connectWhatsApp("pn-b", "waba-b", tokenCipher.encrypt(clinic.getId(), "token-of-A"));
        clinics.save(other);

        SendOutcome outcome = messenger.send(other.getId(), new TextMessage(PHONE, "hello"), null);

        assertThat(outcome.result()).isEqualTo(Result.FAILED);
        assertThat(errorTitle(outcome)).isEqualTo("The clinic's access token can't be decrypted");
        assertThat(whatsApp.recent()).isEmpty();
    }

    private String errorTitle(SendOutcome outcome) {
        return jdbc.queryForObject("select error_title from message_log where id = ?", String.class, outcome.messageLogId());
    }

    private int messageLogCount() {
        return jdbc.queryForObject("select count(*) from message_log", Integer.class);
    }
}
