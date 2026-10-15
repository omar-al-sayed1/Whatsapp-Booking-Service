package app.clinicreminders.whatsapp.dev;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.whatsapp.FakeWhatsAppClient.SentMessage;
import app.clinicreminders.whatsapp.MessageKind;
import app.clinicreminders.whatsapp.MessageLogRepository;
import app.clinicreminders.whatsapp.MessageStatus;
import app.clinicreminders.whatsapp.OutboundMessage.ReplyButtonsMessage;
import app.clinicreminders.whatsapp.OutboundMessage.TemplateMessage;
import app.clinicreminders.whatsapp.WhatsAppMessenger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

class DevWhatsAppPageIT extends IntegrationTest {

    private static final String PATIENT = "+96170000001";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    WhatsAppMessenger messenger;

    @Autowired
    MessageLogRepository logs;

    private Clinic clinic;

    @BeforeEach
    void devClinic() {
        clinic = data.connectedClinic("Dev Clinic", "pn-dev");
        data.patient(clinic, PATIENT);
    }

    @Test
    void showsTheSimulator() {
        assertThat(mvc.get().uri("/dev/whatsapp"))
                .hasStatusOk()
                .bodyText().contains("WhatsApp simulator", "Dev Clinic", "Nothing sent yet.");
    }

    @Test
    void sendsAsTheClinicAndListsTheMessage() {
        assertThat(mvc.post().uri("/dev/whatsapp/send")
                .param("clinicId", clinic.getId().toString())
                .param("to", PATIENT)
                .param("text", "Your appointment is tomorrow"))
                .hasStatus3xxRedirection();

        assertThat(whatsApp.recent()).hasSize(1);
        assertThat(mvc.get().uri("/dev/whatsapp")).bodyText().contains("Your appointment is tomorrow", "SENT");
    }

    @Test
    void sendsReplyButtonsAndSkipsBlankTitles() {
        mvc.post().uri("/dev/whatsapp/send-buttons")
                .param("clinicId", clinic.getId().toString())
                .param("to", PATIENT)
                .param("text", "Keep the appointment?")
                .param("titles", "Yes", "No", "")
                .exchange();

        assertThat(whatsApp.recent().getFirst().message()).isInstanceOfSatisfying(ReplyButtonsMessage.class,
                message -> assertThat(message.buttons()).hasSize(2));
    }

    @Test
    void repliesAsThePatientThroughTheInbox() {
        mvc.post().uri("/dev/whatsapp/reply")
                .param("clinicId", clinic.getId().toString())
                .param("from", PATIENT)
                .param("text", "Hello")
                .exchange();

        assertThat(jdbc.queryForObject("select count(*) from message_log where direction = 'IN' and kind = 'TEXT'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from webhook_inbox", Integer.class)).isZero();
    }

    @Test
    void tapsATemplatesQuickReplyAndMarksItRead() {
        String id = messenger.send(clinic.getId(), new TemplateMessage(PATIENT, "appointment_reminder", "ar",
                List.of("Rana", "د. خوري", "الاثنين", "09:00")), null).waMessageId();

        mvc.post().uri("/dev/whatsapp/tap").param("waMessageId", id).param("label", "تأكيد").exchange();
        mvc.post().uri("/dev/whatsapp/status").param("waMessageId", id).param("status", "read").exchange();

        assertThat(jdbc.queryForObject("select kind from message_log where direction = 'IN'", String.class))
                .isEqualTo(MessageKind.BUTTON_REPLY.name());
        assertThat(logs.findByClinicIdAndWaMessageId(clinic.getId(), id).orElseThrow().getStatus())
                .isEqualTo(MessageStatus.READ);
    }

    @Test
    void canMakeTheNextSendFail() {
        mvc.post().uri("/dev/whatsapp/fail-next").param("kind", "permanent").exchange();
        mvc.post().uri("/dev/whatsapp/send")
                .param("clinicId", clinic.getId().toString())
                .param("to", PATIENT)
                .param("text", "hello")
                .exchange();

        assertThat(whatsApp.recent()).extracting(SentMessage::waMessageId).isEmpty();
        assertThat(jdbc.queryForObject("select status from message_log", String.class)).isEqualTo("FAILED");
    }
}
