package app.clinicreminders.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentService;
import app.clinicreminders.appointment.AppointmentSource;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.whatsapp.FakeWhatsAppClient.SentMessage;
import app.clinicreminders.whatsapp.MessageLog;
import app.clinicreminders.whatsapp.MessageLogRepository;
import app.clinicreminders.whatsapp.MessageStatus;
import app.clinicreminders.whatsapp.OutboundMessage.TemplateMessage;
import app.clinicreminders.whatsapp.WhatsAppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The scheduler's work: SPEC section 5.1.3-4, and "at most once" from CLAUDE.md. */
class ReminderSenderIT extends IntegrationTest {

    /** Tuesday 3 November 2026, 10:30 in Beirut (UTC+2). "Now" starts on Wednesday 14 October. */
    private static final Instant TUE_1030 = Instant.parse("2026-11-03T08:30:00Z");
    private static final Instant H24_DUE = Instant.parse("2026-11-02T08:30:00Z");
    private static final Instant H2_DUE = Instant.parse("2026-11-03T06:30:00Z");

    @Autowired
    ReminderSender sender;

    @Autowired
    AppointmentService appointments;

    @Autowired
    ReminderRepository reminders;

    @Autowired
    PatientRepository patients;

    @Autowired
    ClinicRepository clinics;

    @Autowired
    MessageLogRepository logs;

    private Clinic clinic;
    private Doctor doctor;
    private Patient rana;

    @BeforeEach
    void clinicWithDoctorAndPatient() {
        clinic = data.connectedClinic("A", "pn-a");
        doctor = data.doctor(clinic, "د. خوري", 30);
        rana = data.patient(clinic, "Rana", "+96170123456", Language.AR);
    }

    @Test
    void theH24GoesOutWhenDue() {
        Appointment appointment = book(rana, TUE_1030);
        Instant now = H24_DUE.plusSeconds(20);
        clock.setInstant(now);

        assertThat(sender.sendDue(10)).isEqualTo(1);

        SentMessage sent = whatsApp.recent().getFirst();
        assertThat(sent.message()).isInstanceOfSatisfying(TemplateMessage.class, template -> {
            assertThat(template.name()).isEqualTo("appointment_reminder");
            assertThat(template.languageCode()).isEqualTo("ar");
            assertThat(template.bodyParameters()).containsExactly("Rana", "د. خوري", "الثلاثاء 3 تشرين الثاني", "10:30 ص");
        });
        Reminder h24 = reminder(appointment, ReminderKind.H24);
        assertThat(h24.getStatus()).isEqualTo(ReminderStatus.SENT);
        assertThat(h24.getSentMessageId()).isEqualTo(sent.waMessageId());
        assertThat(h24.getSentAt()).isEqualTo(now);
        assertThat(h24.getAttempts()).isEqualTo(1);
        assertThat(reminder(appointment, ReminderKind.H2).getStatus()).isEqualTo(ReminderStatus.PENDING);
        MessageLog log = logs.findByClinicIdAndWaMessageId(clinic.getId(), sent.waMessageId()).orElseThrow();
        assertThat(log.getAppointmentId()).isEqualTo(appointment.getId());
        assertThat(log.getTemplateName()).isEqualTo("appointment_reminder");
    }

    @Test
    void nothingGoesOutBeforeItIsDue() {
        book(rana, TUE_1030);
        clock.setInstant(H24_DUE.minusSeconds(1));

        assertThat(sender.sendDue(10)).isZero();
        assertThat(whatsApp.recent()).isEmpty();
    }

    @Test
    void theH2GoesOutInThePatientsLanguageAndALateH24IsSkipped() {
        Patient john = data.patient(clinic, "John", "+96170654321", Language.EN);
        Appointment appointment = book(john, TUE_1030);
        clock.setInstant(H2_DUE);

        // The H24 is overdue too, but on the day itself "tomorrow" would be wrong.
        assertThat(sender.sendDue(10)).isEqualTo(2);

        assertThat(whatsApp.recent()).singleElement().extracting(SentMessage::message)
                .isInstanceOfSatisfying(TemplateMessage.class, template -> {
                    assertThat(template.name()).isEqualTo("appointment_reminder_soon");
                    assertThat(template.languageCode()).isEqualTo("en");
                    assertThat(template.bodyParameters()).containsExactly("John", "د. خوري", "10:30 AM");
                });
        assertThat(reminder(appointment, ReminderKind.H24).getStatus()).isEqualTo(ReminderStatus.SKIPPED);
        assertThat(reminder(appointment, ReminderKind.H2).getStatus()).isEqualTo(ReminderStatus.SENT);
    }

    @Test
    void aCancelledAppointmentIsNeverReminded() {
        Appointment appointment = book(rana, TUE_1030);
        // Cancelled behind the service's back, as if the reminder was claimed while it was being cancelled.
        jdbc.update("update appointment set status = 'CANCELLED', cancelled_at = now() where id = ?", appointment.getId());
        clock.setInstant(H24_DUE);

        sender.sendDue(10);

        assertThat(reminder(appointment, ReminderKind.H24).getStatus()).isEqualTo(ReminderStatus.CANCELLED);
        assertThat(whatsApp.recent()).isEmpty();
    }

    @Test
    void anOptedOutPatientIsSkipped() {
        Appointment appointment = book(rana, TUE_1030);
        rana.optOut();
        patients.save(rana);
        clock.setInstant(H24_DUE);

        sender.sendDue(10);

        assertThat(reminder(appointment, ReminderKind.H24).getStatus()).isEqualTo(ReminderStatus.SKIPPED);
        assertThat(whatsApp.recent()).isEmpty();
    }

    @Test
    void aDeletedPatientIsSkipped() {
        Appointment appointment = book(rana, TUE_1030);
        jdbc.update("delete from patient where id = ?", rana.getId());
        clock.setInstant(H24_DUE);

        sender.sendDue(10);

        assertThat(reminder(appointment, ReminderKind.H24).getStatus()).isEqualTo(ReminderStatus.SKIPPED);
        assertThat(whatsApp.recent()).isEmpty();
    }

    @Test
    void remindersSwitchedOffAfterBookingAreSkipped() {
        Appointment appointment = book(rana, TUE_1030);
        clinic.configureReminders(false, true);
        clinics.save(clinic);
        clock.setInstant(H24_DUE);

        sender.sendDue(10);

        assertThat(reminder(appointment, ReminderKind.H24).getStatus()).isEqualTo(ReminderStatus.SKIPPED);
    }

    @Test
    void runningLateIntoQuietHoursSkipsTheReminder() {
        Appointment appointment = book(rana, TUE_1030);
        clock.setInstant(Instant.parse("2026-11-02T19:30:00Z")); // 21:30 Monday in Beirut

        sender.sendDue(10);

        assertThat(reminder(appointment, ReminderKind.H24).getStatus()).isEqualTo(ReminderStatus.SKIPPED);
        assertThat(whatsApp.recent()).isEmpty();
    }

    @Test
    void remindersForAnAppointmentThatStartedAreSkipped() {
        Appointment appointment = book(rana, TUE_1030);
        clock.setInstant(TUE_1030.plusSeconds(60));

        assertThat(sender.sendDue(10)).isEqualTo(2);

        assertThat(reminders.findByClinicIdAndAppointmentIdOrderByDueAt(clinic.getId(), appointment.getId()))
                .extracting(Reminder::getStatus).containsOnly(ReminderStatus.SKIPPED);
        assertThat(whatsApp.recent()).isEmpty();
    }

    @Test
    void aRefusalFromMetaMarksItFailedAndItIsNotRetried() {
        Appointment appointment = book(rana, TUE_1030);
        whatsApp.failNext(WhatsAppException.fromMetaError(131026, "Message undeliverable"));
        clock.setInstant(H24_DUE);

        sender.sendDue(10);
        sender.sendDue(10);

        assertThat(reminder(appointment, ReminderKind.H24).getStatus()).isEqualTo(ReminderStatus.FAILED);
        assertThat(whatsApp.recent()).isEmpty();
        assertThat(jdbc.queryForObject("select status from message_log", String.class)).isEqualTo(MessageStatus.FAILED.name());
    }

    @Test
    void aReminderLeftSendingByACrashIsNeverSentAgain() {
        Appointment appointment = book(rana, TUE_1030);
        jdbc.update("update reminder set status = 'SENDING', attempts = 1 where kind = 'H24'");
        clock.setInstant(H24_DUE.plus(Duration.ofHours(1)));

        assertThat(sender.sendDue(10)).isZero();

        assertThat(reminder(appointment, ReminderKind.H24).getStatus()).isEqualTo(ReminderStatus.SENDING);
        assertThat(whatsApp.recent()).isEmpty();
    }

    @Test
    void twoWorkersSendEveryReminderExactlyOnce() throws Exception {
        Doctor second = data.doctor(clinic, "Dr. Saab", 30);
        List<Appointment> booked = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Instant start = Instant.parse("2026-11-03T07:00:00Z").plus(Duration.ofMinutes(30L * i)); // 09:00-13:30
            booked.add(book(rana, start));
            booked.add(appointments.book(clinic.getId(), second.getId(), rana.getId(), start, AppointmentSource.MANUAL));
        }
        clock.setInstant(Instant.parse("2026-11-02T12:00:00Z")); // 14:00 Monday: all 20 H24s are due

        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> runs = List.of(
                    workers.submit(() -> {
                        go.await();
                        return sender.sendDue(100);
                    }),
                    workers.submit(() -> {
                        go.await();
                        return sender.sendDue(100);
                    }));
            go.countDown();
            assertThat(runs.get(0).get() + runs.get(1).get()).isEqualTo(20);
        } finally {
            workers.shutdownNow();
        }

        assertThat(whatsApp.recent()).hasSize(20);
        assertThat(jdbc.queryForObject("select count(distinct appointment_id) from message_log", Integer.class))
                .isEqualTo(20);
        for (Appointment appointment : booked) {
            Reminder h24 = reminder(appointment, ReminderKind.H24);
            assertThat(h24.getStatus()).isEqualTo(ReminderStatus.SENT);
            assertThat(h24.getAttempts()).isEqualTo(1);
        }
    }

    private Appointment book(Patient patient, Instant startsAt) {
        return appointments.book(clinic.getId(), doctor.getId(), patient.getId(), startsAt, AppointmentSource.WHATSAPP);
    }

    private Reminder reminder(Appointment appointment, ReminderKind kind) {
        return reminders.findByClinicIdAndAppointmentIdOrderByDueAt(clinic.getId(), appointment.getId()).stream()
                .filter(r -> r.getKind() == kind)
                .findFirst()
                .orElseThrow();
    }
}
