package app.clinicreminders.whatsapp.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.whatsapp.MessageKind;
import app.clinicreminders.whatsapp.MessageLog;
import app.clinicreminders.whatsapp.MessageLogRepository;
import app.clinicreminders.whatsapp.MessageStatus;
import app.clinicreminders.whatsapp.OutboundMessage.TemplateMessage;
import app.clinicreminders.whatsapp.OutboundMessage.TextMessage;
import app.clinicreminders.whatsapp.WhatsAppMessenger;
import app.clinicreminders.whatsapp.WhatsAppMessenger.Result;
import app.clinicreminders.whatsapp.dev.SimulatedWebhooks;
import app.clinicreminders.whatsapp.webhook.InboundContent.QuickReply;
import app.clinicreminders.whatsapp.webhook.InboundContent.Text;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The webhook end to end: HTTP, signature, inbox, processing, events. */
@RecordApplicationEvents
class WhatsAppWebhookIT extends IntegrationTest {

    private static final String PHONE_NUMBER_ID = "pn-clinic-a";
    private static final String PATIENT_PHONE = "+96170123456";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    WebhookSignatureVerifier signatures;

    @Autowired
    WebhookInbox inbox;

    @Autowired
    WebhookInboxProcessor processor;

    @Autowired
    WhatsAppMessenger messenger;

    @Autowired
    MessageLogRepository logs;

    @Autowired
    PatientRepository patients;

    @Autowired
    ApplicationEvents events;

    private Clinic clinic;
    private Patient patient;

    @BeforeEach
    void clinicWithPatient() {
        clinic = data.connectedClinic("A", PHONE_NUMBER_ID);
        patient = data.patient(clinic, PATIENT_PHONE);
    }

    @Test
    void verificationEchoesTheChallenge() {
        assertThat(mvc.get().uri("/webhooks/whatsapp")
                .param("hub.mode", "subscribe")
                .param("hub.verify_token", "test-verify-token")
                .param("hub.challenge", "1158201444"))
                .hasStatusOk()
                .hasBodyTextEqualTo("1158201444");
    }

    @Test
    void verificationRejectsAWrongTokenOrMode() {
        assertThat(mvc.get().uri("/webhooks/whatsapp")
                .param("hub.mode", "subscribe")
                .param("hub.verify_token", "guess")
                .param("hub.challenge", "1158201444"))
                .hasStatus(403);
        assertThat(mvc.get().uri("/webhooks/whatsapp")
                .param("hub.mode", "unsubscribe")
                .param("hub.verify_token", "test-verify-token")
                .param("hub.challenge", "1158201444"))
                .hasStatus(403);
    }

    @Test
    void aSignedDeliveryIsStoredAndAcknowledged() {
        assertThat(post(text("hello"))).hasStatusOk();

        assertThat(inboxSize()).isEqualTo(1);
    }

    @Test
    void unsignedOrBadlySignedDeliveriesAreRejectedAndNotStored() {
        byte[] body = text("hello").getBytes(StandardCharsets.UTF_8);

        assertThat(mvc.post().uri("/webhooks/whatsapp").contentType(MediaType.APPLICATION_JSON).content(body))
                .hasStatus(401);
        assertThat(mvc.post().uri("/webhooks/whatsapp").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Hub-Signature-256", signatures.sign("{}".getBytes(StandardCharsets.UTF_8))))
                .hasStatus(401);
        assertThat(inboxSize()).isZero();
    }

    @Test
    void anInboundMessageIsLoggedAndPublished() {
        String id = SimulatedWebhooks.newMessageId();
        post(SimulatedWebhooks.text(PHONE_NUMBER_ID, PATIENT_PHONE, "Rana", id, "مرحبا", clock.instant()));

        assertThat(processor.drain()).isEqualTo(1);

        MessageLog log = logs.findByClinicIdAndWaMessageId(clinic.getId(), id).orElseThrow();
        assertThat(log.getKind()).isEqualTo(MessageKind.TEXT);
        List<InboundMessageReceived> received = received();
        assertThat(received).singleElement().satisfies(event -> {
            assertThat(event.clinicId()).isEqualTo(clinic.getId());
            assertThat(event.patientId()).isEqualTo(patient.getId());
            assertThat(event.messageLogId()).isEqualTo(log.getId());
            assertThat(event.message().content()).isEqualTo(new Text("مرحبا"));
        });
        assertThat(inboxSize()).isZero();
    }

    @Test
    void duplicateDeliveriesAreProcessedOnce() {
        String payload = text("hello");
        post(payload);
        post(payload);
        processor.drain();
        post(payload);
        processor.drain();

        assertThat(inboundCount()).isEqualTo(1);
        assertThat(received()).hasSize(1);
        assertThat(inboxSize()).isZero();
    }

    @Test
    void deliveriesForAnUnknownNumberAreDropped() {
        post(SimulatedWebhooks.text("pn-unknown", PATIENT_PHONE, null, SimulatedWebhooks.newMessageId(), "hi",
                clock.instant()));

        processor.drain();

        assertThat(inboundCount()).isZero();
        assertThat(received()).isEmpty();
        assertThat(inboxSize()).isZero();
    }

    @Test
    void aTemplateButtonTapPointsBackToTheTemplateMessage() {
        String reminderId = messenger.send(clinic.getId(), new TemplateMessage(PATIENT_PHONE, "appointment_reminder",
                "en", List.of("Rana", "Dr. Khoury", "Monday 2 November", "09:00")), null).waMessageId();

        post(SimulatedWebhooks.quickReply(PHONE_NUMBER_ID, PATIENT_PHONE, "Rana", SimulatedWebhooks.newMessageId(),
                reminderId, "Confirm", clock.instant()));
        processor.drain();

        assertThat(received()).singleElement().satisfies(event -> {
            assertThat(event.message().contextMessageId()).isEqualTo(reminderId);
            assertThat(event.message().content()).isEqualTo(new QuickReply("Confirm", "Confirm"));
        });
    }

    @Test
    void stopOptsOutAKnownPatientAndIsNotPublished() {
        post(text("STOP"));
        processor.drain();

        assertThat(patients.findByClinicIdAndPhoneE164OrderById(clinic.getId(), PATIENT_PHONE).getFirst().isOptedOut()).isTrue();
        assertThat(received()).isEmpty();
        assertThat(messenger.send(clinic.getId(), new TextMessage(PATIENT_PHONE, "hello"), null).result())
                .isEqualTo(Result.OPTED_OUT);
    }

    @Test
    void arabicStopFromAnUnknownNumberCreatesAnOptedOutPatient() {
        post(SimulatedWebhooks.text(PHONE_NUMBER_ID, "+96171999888", "Karim", SimulatedWebhooks.newMessageId(), "توقف",
                clock.instant()));
        processor.drain();

        Patient created = patients.findByClinicIdAndPhoneE164OrderById(clinic.getId(), "+96171999888").getFirst();
        assertThat(created.isOptedOut()).isTrue();
        assertThat(created.getLanguage()).isEqualTo(Language.AR);
        assertThat(created.getName()).isEqualTo("Karim");
    }

    @Test
    void aMessageWithoutThePhoneNumberIsMatchedByWhatsAppUserId() {
        patient.linkWhatsAppUser("LB.99887766554433221100");
        patients.save(patient);

        post(WebhookPayloadParserTest.read("hidden-number.json")
                .replace("106540352242922", PHONE_NUMBER_ID)
                .replace("\"body\": \"STOP\"", "\"body\": \"hello\""));
        processor.drain();

        assertThat(received()).singleElement().satisfies(event -> {
            assertThat(event.patientId()).isEqualTo(patient.getId());
            assertThat(event.message().phoneE164()).isNull();
        });
    }

    @Test
    void statusesMoveTheMessageForwardOnly() {
        String id = messenger.send(clinic.getId(), new TextMessage(PATIENT_PHONE, "hello"), null).waMessageId();

        deliverStatus(id, "delivered");
        assertThat(statusOf(id)).isEqualTo(MessageStatus.DELIVERED);
        deliverStatus(id, "read");
        deliverStatus(id, "delivered");
        assertThat(statusOf(id)).isEqualTo(MessageStatus.READ);
    }

    @Test
    void aFailedStatusRecordsMetasError() {
        String id = messenger.send(clinic.getId(), new TextMessage(PATIENT_PHONE, "hello"), null).waMessageId();

        post(SimulatedWebhooks.status(PHONE_NUMBER_ID, id, PATIENT_PHONE, "failed", null, null, 131026,
                "Message undeliverable", clock.instant()));
        processor.drain();

        MessageLog log = logs.findByClinicIdAndWaMessageId(clinic.getId(), id).orElseThrow();
        assertThat(log.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(log.getErrorCode()).isEqualTo(131026);
        assertThat(log.getErrorTitle()).isEqualTo("Message undeliverable");
    }

    @Test
    void metasPricingCorrectsTheEstimate() {
        data.rate("Rest of Middle East", "service", "0.0080", LocalDate.of(2026, 10, 1));
        String id = messenger.send(clinic.getId(), new TextMessage(PATIENT_PHONE, "hello"), null).waMessageId();
        assertThat(costOf(id)).isEqualByComparingTo("0.0080");

        post(SimulatedWebhooks.status(PHONE_NUMBER_ID, id, PATIENT_PHONE, "delivered", "service",
                "free_customer_service", null, null, clock.instant()));
        processor.drain();

        assertThat(costOf(id)).isEqualByComparingTo("0");
    }

    @Test
    void metasStatusFixtureRunsThroughTheWholePipeline() {
        Clinic fixtureClinic = data.connectedClinic("Fixture", "106540352242922");
        data.patient(fixtureClinic, "+96170123456");
        logs.save(MessageLog.sent(fixtureClinic.getId(), null, MessageKind.TEMPLATE, "appointment_reminder",
                "wamid.TEMPLATE-REMINDER-1", "utility", new BigDecimal("0.0110")));

        post(WebhookPayloadParserTest.read("statuses.json"));
        processor.drain();

        MessageLog log = logs.findByClinicIdAndWaMessageId(fixtureClinic.getId(), "wamid.TEMPLATE-REMINDER-1").orElseThrow();
        assertThat(log.getStatus()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(log.getPricingCategory()).isEqualTo("utility");
        assertThat(patients.findByClinicIdAndPhoneE164OrderById(fixtureClinic.getId(), "+96170123456").getFirst().getWaUserId())
                .isEqualTo("LB.13491208655302741918");
        assertThat(inboxSize()).isZero();
    }

    @Test
    void aDeliveryThatKeepsFailingIsParkedWithoutBlockingOthers() {
        inbox.store("not json");
        post(text("hello"));

        assertThat(processor.drain()).isEqualTo(2);
        assertThat(inboundCount()).isEqualTo(1);
        for (int i = 0; i < 4; i++) {
            processor.drain();
        }

        Map<String, Object> parked = jdbc.queryForMap("select attempts, failed, last_error from webhook_inbox");
        assertThat(parked.get("attempts")).isEqualTo(5);
        assertThat(parked.get("failed")).isEqualTo(true);
        assertThat((String) parked.get("last_error")).isNotBlank();
        assertThat(processor.drain()).isZero();
    }

    // --- helpers ---

    private String text(String body) {
        return SimulatedWebhooks.text(PHONE_NUMBER_ID, PATIENT_PHONE, "Rana", "wamid.TEXT-" + body.hashCode(), body,
                clock.instant());
    }

    private MvcTestResult post(String payload) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        return mvc.post().uri("/webhooks/whatsapp")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature-256", signatures.sign(body))
                .content(body)
                .exchange();
    }

    private void deliverStatus(String waMessageId, String status) {
        post(SimulatedWebhooks.status(PHONE_NUMBER_ID, waMessageId, PATIENT_PHONE, status, "service", "regular",
                null, null, clock.instant()));
        processor.drain();
    }

    private MessageStatus statusOf(String waMessageId) {
        return logs.findByClinicIdAndWaMessageId(clinic.getId(), waMessageId).orElseThrow().getStatus();
    }

    private BigDecimal costOf(String waMessageId) {
        return logs.findByClinicIdAndWaMessageId(clinic.getId(), waMessageId).orElseThrow().getEstCostUsd();
    }

    private List<InboundMessageReceived> received() {
        return events.stream(InboundMessageReceived.class).toList();
    }

    private int inboxSize() {
        return jdbc.queryForObject("select count(*) from webhook_inbox", Integer.class);
    }

    private int inboundCount() {
        return jdbc.queryForObject("select count(*) from message_log where direction = 'IN'", Integer.class);
    }
}
