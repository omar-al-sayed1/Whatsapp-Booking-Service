package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentEvents.CancelledBy;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentService;
import app.clinicreminders.appointment.AppointmentSource;
import app.clinicreminders.appointment.AppointmentStatus;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.reminder.Reminder;
import app.clinicreminders.reminder.ReminderRepository;
import app.clinicreminders.reminder.ReminderStatus;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.staff.StaffPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** "Now" is Wednesday 14 October 2026, 12:00 in Beirut (09:00Z). */
class TodayPageIT extends DashboardTest {

    private static final Instant TODAY_3PM = Instant.parse("2026-10-14T12:00:00Z");
    private static final Instant TODAY_10AM = Instant.parse("2026-10-14T07:00:00Z");

    @Autowired
    AppointmentService appointmentService;

    @Autowired
    AppointmentRepository appointments;

    @Autowired
    ReminderRepository reminders;

    private Clinic clinic;
    private Doctor doctor;
    private Patient rana;
    private StaffPrincipal staff;

    @BeforeEach
    void clinicWithPatient() {
        clinic = data.connectedClinic("A", "pn-a");
        doctor = data.doctor(clinic, "Dr. Khoury", 30);
        rana = data.patient(clinic, "Rana", "+96170123456", Language.AR);
        staff = secretaryOf(clinic);
    }

    @Test
    void listsTheDaysAppointmentsPerDoctorAndOnlyThisClinics() {
        book(TODAY_3PM);
        Clinic other = data.clinic("B");
        data.appointment(data.doctor(other, "Dr. Elsewhere", 30), data.patient(other, "Someone Else", "+96171000000", Language.EN),
                TODAY_3PM, Duration.ofMinutes(30));

        assertThat(get(staff, "/")).hasStatusOk().bodyText()
                .contains("Dr. Khoury", "Rana", "+96170123456", "3:00 PM", "Booked")
                .doesNotContain("Someone Else", "Dr. Elsewhere");
    }

    @Test
    void otherDaysByDate() {
        book(TODAY_3PM.plus(Duration.ofDays(1)));

        assertThat(get(staff, "/")).bodyText().doesNotContain("Rana");
        assertThat(get(staff, "/?date=2026-10-15")).bodyText().contains("Rana", "Thursday 15 October 2026");
    }

    @Test
    void theRefreshReturnsOnlyTheList() {
        book(TODAY_3PM);

        assertThat(get(staff, "/today/list?date=2026-10-14")).hasStatusOk().bodyText()
                .contains("id=\"today-list\"", "Rana")
                .doesNotContain("<html");
    }

    @Test
    void markingArrivedWithHtmxReturnsTheRefreshedList() {
        Appointment appointment = book(TODAY_3PM);

        assertThat(htmxPost(staff, "/appointments/{id}/arrived", appointment.getId()))
                .hasStatusOk().bodyText().contains("id=\"today-list\"", "Arrived");
        assertThat(status(appointment)).isEqualTo(AppointmentStatus.ARRIVED);
    }

    @Test
    void noShowOnceItHasStarted() {
        Appointment appointment = book(TODAY_10AM);

        htmxPost(staff, "/appointments/{id}/no-show", appointment.getId()).exchange();

        assertThat(status(appointment)).isEqualTo(AppointmentStatus.NO_SHOW);
    }

    @Test
    void cancellingCancelsTheRemindersAndDoesNotMessageThePatient() {
        Appointment appointment = book(TODAY_3PM);

        assertThat(post(staff, "/appointments/{id}/cancel", appointment.getId()))
                .hasStatus3xxRedirection()
                .headers().hasValue("Location", "/?date=2026-10-14");

        Appointment cancelled = appointments.findByIdAndClinicId(appointment.getId(), clinic.getId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(cancelled.getCancelledBy()).isEqualTo(CancelledBy.STAFF);
        assertThat(reminders.findByClinicIdAndAppointmentIdOrderByDueAt(clinic.getId(), appointment.getId()))
                .extracting(Reminder::getStatus).doesNotContain(ReminderStatus.PENDING);
        assertThat(whatsApp.recent()).isEmpty();
    }

    @Test
    void cancellationsByThePatientStandOut() {
        data.cancelledAppointment(doctor, rana, TODAY_3PM, Duration.ofMinutes(30));

        assertThat(get(staff, "/")).bodyText().contains("Cancelled by patient", "chip-patient-cancelled");
    }

    @Test
    void anActionThatNoLongerAppliesRefreshesThePage() {
        Appointment appointment = book(TODAY_3PM);
        htmxPost(staff, "/appointments/{id}/cancel", appointment.getId()).exchange();

        assertThat(htmxPost(staff, "/appointments/{id}/cancel", appointment.getId()))
                .headers().hasValue("HX-Refresh", "true");
    }

    @Test
    void anotherClinicsAppointmentCannotBeTouched() {
        Clinic other = data.clinic("B");
        Appointment theirs = data.appointment(data.doctor(other, 30), data.patient(other), TODAY_3PM, Duration.ofMinutes(30));

        assertThat(post(staff, "/appointments/{id}/arrived", theirs.getId())).hasStatus(404);
        assertThat(post(staff, "/appointments/{id}/cancel", theirs.getId())).hasStatus(404);
        assertThat(appointments.findByIdAndClinicId(theirs.getId(), other.getId()).orElseThrow().getStatus())
                .isEqualTo(AppointmentStatus.BOOKED);
    }

    private Appointment book(Instant startsAt) {
        return appointmentService.book(clinic.getId(), doctor.getId(), rana.getId(), startsAt, AppointmentSource.WHATSAPP);
    }

    private AppointmentStatus status(Appointment appointment) {
        return appointments.findByIdAndClinicId(appointment.getId(), clinic.getId()).orElseThrow().getStatus();
    }
}
