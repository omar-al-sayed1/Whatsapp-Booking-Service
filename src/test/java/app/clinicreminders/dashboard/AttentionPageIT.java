package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.booking.Conversation;
import app.clinicreminders.booking.ConversationRepository;
import app.clinicreminders.booking.ConversationState;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.staff.StaffPrincipal;
import app.clinicreminders.whatsapp.MessageKind;
import app.clinicreminders.whatsapp.MessageLog;
import app.clinicreminders.whatsapp.MessageLogRepository;
import app.clinicreminders.whatsapp.OutboundMessage.TemplateMessage;
import app.clinicreminders.whatsapp.WhatsAppException;
import app.clinicreminders.whatsapp.WhatsAppMessenger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AttentionPageIT extends DashboardTest {

    @Autowired
    ConversationRepository conversations;

    @Autowired
    WhatsAppMessenger messenger;

    @Autowired
    MessageLogRepository logs;

    private Clinic clinic;
    private Patient rana;
    private StaffPrincipal staff;

    @BeforeEach
    void clinicWithPatient() {
        clinic = data.connectedClinic("A", "pn-a");
        rana = data.patient(clinic, "Rana", "+96170123456", Language.AR);
        staff = secretaryOf(clinic);
    }

    @Test
    void listsPatientsWaitingForACallAndFailedMessages() {
        flagged(clinic, "+96170123456");
        Appointment appointment = data.appointment(data.doctor(clinic, 30), rana, Instant.parse("2026-11-03T08:30:00Z"),
                Duration.ofMinutes(30));
        whatsApp.failNext(WhatsAppException.fromMetaError(131026, "Message undeliverable"));
        messenger.send(clinic.getId(), new TemplateMessage("+96170123456", "appointment_reminder", "ar", List.of()),
                appointment.getId());
        Clinic other = data.clinic("B");
        flagged(other, "+96171000000");

        assertThat(get(staff, "/attention")).hasStatusOk().bodyText()
                .contains("Rana", "+96170123456", "Message undeliverable (131026)", "Reminder")
                .contains("Last 7 days", "These patients did not get the message", "class=\"error-line\"")
                .doesNotContain("+96171000000");
    }

    /** The patient is known through the appointment (message_log keeps no phone numbers of its own). */
    @Test
    void aFailedReminderOffersACallToThePatient() {
        Appointment appointment = data.appointment(data.doctor(clinic, 30), rana, Instant.parse("2026-11-03T08:30:00Z"),
                Duration.ofMinutes(30));
        whatsApp.failNext(WhatsAppException.fromMetaError(131026, "Message undeliverable"));
        messenger.send(clinic.getId(), new TemplateMessage("+96170123456", "appointment_reminder", "ar", List.of()),
                appointment.getId());

        assertThat(get(staff, "/attention")).bodyText()
                .containsOnlyOnce("href=\"tel:+96170123456\"")
                .contains("Call");
    }

    /** Needs attention is a bell beside the account menu, with the count, rather than a sidebar item. */
    @Test
    void theBellBesideTheAccountShowsHowManyNeedAttention() {
        flagged(clinic, "+96170123456");

        assertThat(get(staff, "/")).bodyText()
                .contains("class=\"bell has-alerts\" href=\"/attention\"", "aria-label=\"Needs attention (1)\"")
                .as("on phones: the first item of the account menu, and a dot on the avatar")
                .contains("class=\"menu-attention\" href=\"/attention\"", "class=\"avatar-alert\"");
        assertThat(get(staff, "/attention")).bodyText()
                .contains("class=\"bell active has-alerts\" href=\"/attention\"",
                        "aria-label=\"Needs attention (1)\" aria-current=\"page\"",
                        "class=\"menu-attention active\" href=\"/attention\"");
    }

    @Test
    void withNothingWaitingTheAvatarHasNoDot() {
        assertThat(get(staff, "/")).bodyText()
                .contains("class=\"menu-attention\" href=\"/attention\"")
                .doesNotContain("class=\"avatar-alert\"", "has-alerts");
    }

    @Test
    void failedMessagesLoadTwentyAtATimeAsYouScroll() {
        for (int i = 0; i < 25; i++) {
            logs.save(MessageLog.failed(clinic.getId(), null, MessageKind.TEMPLATE, "appointment_reminder", 131000 + i, "Failure " + i));
        }

        assertThat(get(staff, "/attention")).bodyText()
                .contains(">25<", "hx-get=\"/attention?failedPage=2\"").doesNotContain("Failure 0 (131000)");
        assertThat(get(staff, "/attention?failedPage=2").header("HX-Request", "true")).bodyText()
                .as("the newest come first; page 2 has the oldest five").contains("Failure 0 (131000)", "Failure 4 (131004)")
                .doesNotContain("<html", "Failure 5 (131005)", "failedPage=3");
    }

    @Test
    void failuresOlderThanAWeekDropOff() {
        whatsApp.failNext(WhatsAppException.fromMetaError(131026, "Message undeliverable"));
        messenger.send(clinic.getId(), new TemplateMessage("+96170123456", "appointment_reminder", "ar", List.of()), null);
        // Eight days before the test clock's "now" (14 October 2026).
        jdbc.update("update message_log set created_at = '2026-10-06T08:00:00Z'");

        assertThat(get(staff, "/attention")).bodyText().contains("No failed messages.");
    }

    @Test
    void markingAConversationDoneTakesItOffTheList() {
        Conversation conversation = flagged(clinic, "+96170123456");

        assertThat(post(staff, "/attention/conversations/{id}/handled", conversation.getId())).hasStatus3xxRedirection();

        assertThat(conversations.findByClinicIdAndNeedsHumanTrueOrderByUpdatedAtDesc(clinic.getId())).isEmpty();
        assertThat(get(staff, "/attention")).bodyText().contains("Nobody is waiting.");
    }

    @Test
    void anotherClinicsConversationCannotBeTouched() {
        Conversation theirs = flagged(data.clinic("B"), "+96171000000");

        assertThat(post(staff, "/attention/conversations/{id}/handled", theirs.getId())).hasStatus(404);
    }

    private Conversation flagged(Clinic owner, String phone) {
        Conversation conversation = new Conversation(owner.getId(), phone, ConversationState.MENU,
                Instant.parse("2026-10-14T09:30:00Z"));
        conversation.flagForHuman();
        return conversations.save(conversation);
    }
}
