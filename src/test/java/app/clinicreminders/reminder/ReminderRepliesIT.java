package app.clinicreminders.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentService;
import app.clinicreminders.appointment.AppointmentSource;
import app.clinicreminders.appointment.AppointmentStatus;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.whatsapp.FakeWhatsAppClient.SentMessage;
import app.clinicreminders.whatsapp.MessageLogRepository;
import app.clinicreminders.whatsapp.OutboundMessage.TextMessage;
import app.clinicreminders.whatsapp.WhatsAppMessenger;
import app.clinicreminders.whatsapp.dev.SimulatedWebhooks;
import app.clinicreminders.whatsapp.webhook.WebhookInbox;
import app.clinicreminders.whatsapp.webhook.WebhookInboxProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Confirm and Cancel taps on a sent reminder, through the real webhook inbox (SPEC section 5.1.5). */
class ReminderRepliesIT extends IntegrationTest {

    private static final Instant TUE_1030 = Instant.parse("2026-11-03T08:30:00Z");
    private static final Instant H24_DUE = Instant.parse("2026-11-02T08:30:00Z");
    private static final String PHONE = "+96170123456";

    @Autowired
    ReminderSender sender;

    @Autowired
    AppointmentService appointmentService;

    @Autowired
    AppointmentRepository appointments;

    @Autowired
    ReminderRepository reminders;

    @Autowired
    MessageLogRepository logs;

    @Autowired
    WhatsAppMessenger messenger;

    @Autowired
    WebhookInbox inbox;

    @Autowired
    WebhookInboxProcessor processor;

    private Clinic clinic;
    private Appointment appointment;
    private String reminderId;

    @BeforeEach
    void aSentReminder() {
        clinic = data.connectedClinic("A", "pn-a");
        Doctor doctor = data.doctor(clinic, "د. خوري", 30);
        Patient rana = data.patient(clinic, "Rana", PHONE, Language.AR);
        appointment = appointmentService.book(clinic.getId(), doctor.getId(), rana.getId(), TUE_1030,
                AppointmentSource.WHATSAPP);
        clock.setInstant(H24_DUE);
        sender.sendDue(10);
        reminderId = whatsApp.recent().getFirst().waMessageId();
        whatsApp.reset();
        clock.setInstant(H24_DUE.plusSeconds(3600));
    }

    @Test
    void confirmConfirmsAndSendsNothing() {
        tap("تأكيد");

        assertThat(status()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(whatsApp.recent()).isEmpty();
        assertThat(h2Status()).isEqualTo(ReminderStatus.PENDING);
    }

    @Test
    void cancelCancelsTheAppointmentAndItsRemindersAndAcknowledgesInThePatientsLanguage() {
        tap("إلغاء");

        assertThat(status()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(h2Status()).isEqualTo(ReminderStatus.CANCELLED);
        SentMessage ack = whatsApp.recent().getFirst();
        assertThat(ack.message()).isEqualTo(new TextMessage(PHONE,
                "تم إلغاء موعدك مع د. خوري يوم الثلاثاء 3 تشرين الثاني الساعة 10:30 ص. راسلنا في أي وقت لحجز موعد جديد."));
        assertThat(logs.findByClinicIdAndWaMessageId(clinic.getId(), ack.waMessageId()).orElseThrow().getAppointmentId())
                .isEqualTo(appointment.getId());
    }

    @Test
    void englishLabelsWorkToo() {
        tap("Confirm");

        assertThat(status()).isEqualTo(AppointmentStatus.CONFIRMED);
    }

    @Test
    void aSecondCancelTapDoesNothing() {
        tap("إلغاء");
        tap("إلغاء");

        assertThat(whatsApp.recent()).hasSize(1);
    }

    @Test
    void cancelAfterConfirmCancelsButConfirmAfterCancelDoesNot() {
        tap("تأكيد");
        tap("إلغاء");
        tap("تأكيد");

        assertThat(status()).isEqualTo(AppointmentStatus.CANCELLED);
    }

    @Test
    void tapsAfterTheAppointmentStartedAreIgnored() {
        clock.setInstant(TUE_1030.plusSeconds(300));

        tap("إلغاء");

        assertThat(status()).isEqualTo(AppointmentStatus.BOOKED);
        assertThat(whatsApp.recent()).isEmpty();
    }

    @Test
    void unknownLabelsAndTapsOnOtherMessagesAreIgnored() {
        tap("Maybe");
        String textId = messenger.send(clinic.getId(), new TextMessage(PHONE, "hello"), appointment.getId()).waMessageId();
        deliver("pn-a", textId, "Cancel");

        assertThat(status()).isEqualTo(AppointmentStatus.BOOKED);
    }

    @Test
    void aTapArrivingForAnotherClinicsNumberCannotTouchThisAppointment() {
        data.connectedClinic("B", "pn-b");

        deliver("pn-b", reminderId, "Cancel");

        assertThat(status()).isEqualTo(AppointmentStatus.BOOKED);
    }

    private void tap(String label) {
        deliver("pn-a", reminderId, label);
    }

    private void deliver(String phoneNumberId, String repliedTo, String label) {
        inbox.store(SimulatedWebhooks.quickReply(phoneNumberId, PHONE, "Rana", SimulatedWebhooks.newMessageId(),
                repliedTo, label, clock.instant()));
        processor.drain();
    }

    private AppointmentStatus status() {
        return appointments.findByIdAndClinicId(appointment.getId(), clinic.getId()).orElseThrow().getStatus();
    }

    private ReminderStatus h2Status() {
        return reminders.findByClinicIdAndAppointmentIdOrderByDueAt(clinic.getId(), appointment.getId()).stream()
                .filter(r -> r.getKind() == ReminderKind.H2).findFirst().orElseThrow().getStatus();
    }
}
