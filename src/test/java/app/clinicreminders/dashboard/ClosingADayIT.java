package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentEvents.CancelledBy;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentService;
import app.clinicreminders.appointment.AppointmentSource;
import app.clinicreminders.appointment.AppointmentStatus;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.reminder.ReminderMessages;
import app.clinicreminders.reminder.ReminderRepository;
import app.clinicreminders.reminder.ReminderStatus;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.SlotService;
import app.clinicreminders.staff.StaffPrincipal;
import app.clinicreminders.whatsapp.FakeWhatsAppClient.SentMessage;
import app.clinicreminders.whatsapp.MessageLogRepository;
import app.clinicreminders.whatsapp.OutboundMessage.ListMessage;
import app.clinicreminders.whatsapp.OutboundMessage.ListRow;
import app.clinicreminders.whatsapp.OutboundMessage.TemplateMessage;
import app.clinicreminders.whatsapp.dev.SimulatedWebhooks;
import app.clinicreminders.whatsapp.webhook.WebhookInbox;
import app.clinicreminders.whatsapp.webhook.WebhookInboxProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * A day off or special hours when patients are already booked then. Now is Wednesday 14 October
 * 2026, 12:00 in Beirut. Dr. Khoury works Wednesdays 09:00-13:00; next Wednesday Rana is booked at
 * 10:30 and Karim at 12:00.
 */
class ClosingADayIT extends DashboardTest {

    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 21);
    private static final String RANA_PHONE = "+96170123456";
    private static final String KARIM_PHONE = "+96171222333";

    @Autowired
    AppointmentService appointmentService;

    @Autowired
    AppointmentRepository appointments;

    @Autowired
    PatientRepository patients;

    @Autowired
    ReminderRepository reminders;

    @Autowired
    MessageLogRepository logs;

    @Autowired
    SlotService slots;

    @Autowired
    WebhookInbox inbox;

    @Autowired
    WebhookInboxProcessor processor;

    private Clinic clinic;
    private Doctor khoury;
    private StaffPrincipal staff;
    private Appointment ranas;
    private Appointment karims;

    @BeforeEach
    void patientsBookedNextWednesday() {
        clinic = data.connectedClinic("A", "pn-a");
        khoury = data.doctor(clinic, "Dr. Khoury", 30);
        data.weekly(khoury, DayOfWeek.WEDNESDAY, "09:00", "13:00");
        staff = secretaryOf(clinic);
        Patient rana = data.patient(clinic, "Rana", RANA_PHONE, Language.AR);
        Patient karim = data.patient(clinic, "Karim", KARIM_PHONE, Language.EN);
        ranas = appointmentService.book(clinic.getId(), khoury.getId(), rana.getId(), Instant.parse("2026-10-21T07:30:00Z"),
                AppointmentSource.MANUAL);
        karims = appointmentService.book(clinic.getId(), khoury.getId(), karim.getId(), Instant.parse("2026-10-21T09:00:00Z"),
                AppointmentSource.WHATSAPP);
    }

    @Test
    void closingADayWithBookingsListsThemFirstAndChangesNothingYet() {
        assertThat(closeWednesday(""))
                .hasRedirectedUrl("/doctors/" + khoury.getId() + "/days/review?date=2026-10-21&closed=true");
        assertThat(jdbc.queryForObject("select count(*) from schedule_exception", Integer.class)).isZero();

        assertThat(get(staff, "/doctors/{id}/days/review?date=2026-10-21&closed=true", khoury.getId()))
                .hasStatusOk().bodyText()
                .contains("Close Wednesday 21 October 2026?", "Patients are booked with Dr. Khoury that day. Appointments: 2.")
                .contains("10:30 AM", "Rana", "12:00 PM", "Karim", "href=\"tel:" + RANA_PHONE + "\"")
                .contains("name=\"appointments\" value=\"cancel\"", "name=\"appointments\" value=\"keep\"",
                        "Cancel them and message the patients", "Keep the appointments");
        assertThat(whatsApp.recent()).isEmpty();
    }

    @Test
    void cancellingThemMessagesEachPatientToBookAnotherTime() {
        assertThat(closeWednesday("cancel")).hasRedirectedUrl("/doctors/" + khoury.getId())
                .flash().containsEntry("notice", "notice.day-cancelled");

        for (Appointment appointment : List.of(ranas, karims)) {
            Appointment cancelled = appointments.findByIdAndClinicId(appointment.getId(), clinic.getId()).orElseThrow();
            assertThat(cancelled.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
            assertThat(cancelled.getCancelledBy()).isEqualTo(CancelledBy.STAFF);
            assertThat(reminders.findByClinicIdAndAppointmentIdOrderByDueAt(clinic.getId(), appointment.getId()))
                    .extracting(r -> r.getStatus()).doesNotContain(ReminderStatus.PENDING);
        }
        assertThat(slots.freeSlots(clinic.getId(), khoury.getId(), WEDNESDAY, WEDNESDAY)).as("closed").isEmpty();
        assertThat(sentTo(RANA_PHONE).message()).isEqualTo(new TemplateMessage(RANA_PHONE, ReminderMessages.DOCTOR_AWAY_TEMPLATE,
                "ar", List.of("Rana", "Dr. Khoury", "الأربعاء 21 تشرين الأول", "10:30 ص")));
        assertThat(sentTo(KARIM_PHONE).message()).isEqualTo(new TemplateMessage(KARIM_PHONE,
                ReminderMessages.DOCTOR_AWAY_TEMPLATE, "en", List.of("Karim", "Dr. Khoury", "Wednesday 21 October", "12:00 PM")));
        assertThat(logs.findByClinicIdAndWaMessageId(clinic.getId(), sentTo(KARIM_PHONE).waMessageId()).orElseThrow()
                .getAppointmentId()).isEqualTo(karims.getId());
    }

    @Test
    void tappingBookAnotherTimeOffersThatDoctorsFreeDays() {
        closeWednesday("cancel");
        SentMessage notice = sentTo(RANA_PHONE);

        inbox.store(SimulatedWebhooks.quickReply("pn-a", RANA_PHONE, "Rana", SimulatedWebhooks.newMessageId(),
                notice.waMessageId(), "احجز موعدًا آخر", clock.instant()));
        processor.drain();

        assertThat(sentTo(RANA_PHONE).message()).isInstanceOfSatisfying(ListMessage.class, days -> {
            assertThat(days.body()).isEqualTo("اختر يومًا.");
            assertThat(days.rows()).extracting(ListRow::id).contains("day:2026-10-28").doesNotContain("day:2026-10-21");
        });
    }

    @Test
    void onAFamilysNumberTheBookingGoesOnInTheLanguageOfTheMessageTapped() {
        Patient lina = data.patient(clinic, "Lina", RANA_PHONE, Language.EN);
        appointmentService.book(clinic.getId(), khoury.getId(), lina.getId(), Instant.parse("2026-10-21T08:00:00Z"),
                AppointmentSource.MANUAL);
        closeWednesday("cancel");
        SentMessage linasNotice = whatsApp.recent().stream()
                .filter(sent -> sent.message() instanceof TemplateMessage template && template.bodyParameters().getFirst().equals("Lina"))
                .findFirst().orElseThrow();

        inbox.store(SimulatedWebhooks.quickReply("pn-a", RANA_PHONE, "Rana", SimulatedWebhooks.newMessageId(),
                linasNotice.waMessageId(), "Book another time", clock.instant()));
        processor.drain();

        assertThat(sentTo(RANA_PHONE).message()).isInstanceOfSatisfying(ListMessage.class,
                days -> assertThat(days.body()).as("Rana, first on the number, is Arabic").isEqualTo("Pick a day."));
    }

    @Test
    void keepingThemOnlyStopsNewBookings() {
        assertThat(closeWednesday("keep")).hasRedirectedUrl("/doctors/" + khoury.getId())
                .flash().containsEntry("notice", "notice.day-kept");

        assertThat(appointments.findByIdAndClinicId(ranas.getId(), clinic.getId()).orElseThrow().getStatus())
                .isEqualTo(AppointmentStatus.BOOKED);
        assertThat(appointments.findByIdAndClinicId(karims.getId(), clinic.getId()).orElseThrow().getStatus())
                .isEqualTo(AppointmentStatus.BOOKED);
        assertThat(slots.freeSlots(clinic.getId(), khoury.getId(), WEDNESDAY, WEDNESDAY)).isEmpty();
        assertThat(whatsApp.recent()).isEmpty();
    }

    @Test
    void shorterHoursLeaveOutOnlyTheAppointmentsOutsideThem() {
        assertThat(post(staff, "/doctors/{id}/days", khoury.getId()).param("date", "2026-10-21")
                .param("start", "09:00").param("end", "11:00"))
                .hasRedirectedUrl("/doctors/" + khoury.getId() + "/days/review?date=2026-10-21&closed=false&start=09%3A00&end=11%3A00");
        assertThat(get(staff, "/doctors/{id}/days/review?date=2026-10-21&start=09:00&end=11:00", khoury.getId()))
                .bodyText().contains("Change the hours on Wednesday 21 October 2026?", "(9:00 AM – 11:00 AM)", "Karim")
                .as("10:30 to 11:00 still fits").doesNotContain("Rana");

        post(staff, "/doctors/{id}/days", khoury.getId()).param("date", "2026-10-21")
                .param("start", "09:00").param("end", "11:00").param("appointments", "cancel").exchange();

        assertThat(appointments.findByIdAndClinicId(ranas.getId(), clinic.getId()).orElseThrow().getStatus())
                .isEqualTo(AppointmentStatus.BOOKED);
        assertThat(appointments.findByIdAndClinicId(karims.getId(), clinic.getId()).orElseThrow().getStatus())
                .isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(whatsApp.recent()).extracting(sent -> sent.message().to()).containsExactly(KARIM_PHONE);
    }

    @Test
    void aDayWithNobodyBookedIsSavedStraightAway() {
        assertThat(post(staff, "/doctors/{id}/days", khoury.getId()).param("date", "2026-10-28").param("closed", "true"))
                .hasRedirectedUrl("/doctors/" + khoury.getId()).flash().containsEntry("notice", "notice.saved");
        assertThat(jdbc.queryForObject("select count(*) from schedule_exception", Integer.class)).isEqualTo(1);
    }

    @Test
    void closingTodayLeavesOutOnlyWhatIsStillToCome() {
        Patient nour = data.patient(clinic, "Nour", "+96103456789", Language.AR);
        data.appointment(khoury, nour, Instant.parse("2026-10-14T06:00:00Z"), Duration.ofMinutes(30));
        Patient lina = data.patient(clinic, "Lina", "+96103456780", Language.AR);
        data.appointment(khoury, lina, Instant.parse("2026-10-14T09:30:00Z"), Duration.ofMinutes(30));

        assertThat(get(staff, "/doctors/{id}/days/review?date=2026-10-14&closed=true", khoury.getId())).bodyText()
                .as("12:30 is still to come; 9:00 is over").contains("Lina").doesNotContain("Nour");
    }

    @Test
    void theDoctorsOwnLoginReviewsTheirDaysButNotAnothers() {
        Doctor saab = data.doctor(clinic, "Dr. Saab", 30);
        StaffPrincipal khourysLogin = doctorLoginOf(khoury);

        assertThat(get(khourysLogin, "/doctors/{id}/days/review?date=2026-10-21&closed=true", khoury.getId()))
                .hasStatusOk().bodyText().contains("Rana");
        assertThat(get(khourysLogin, "/doctors/{id}/days/review?date=2026-10-21&closed=true", saab.getId())).hasStatus(404);
        assertThat(post(khourysLogin, "/doctors/{id}/days", saab.getId()).param("date", "2026-10-21").param("closed", "true")
                .param("appointments", "cancel")).hasStatus(404);
    }

    private MvcTestResult closeWednesday(String appointmentsChoice) {
        var request = post(staff, "/doctors/{id}/days", khoury.getId()).param("date", "2026-10-21").param("closed", "true");
        if (!appointmentsChoice.isEmpty()) {
            request.param("appointments", appointmentsChoice);
        }
        return request.exchange();
    }

    /** The latest message to this number. */
    private SentMessage sentTo(String phone) {
        return whatsApp.recent().stream().filter(sent -> sent.message().to().equals(phone)).findFirst()
                .orElseThrow(() -> new AssertionError("Nothing was sent to " + phone));
    }
}
