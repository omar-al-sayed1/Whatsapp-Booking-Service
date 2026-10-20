package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentService;
import app.clinicreminders.appointment.AppointmentSource;
import app.clinicreminders.appointment.AppointmentStatus;
import app.clinicreminders.booking.Conversation;
import app.clinicreminders.booking.ConversationRepository;
import app.clinicreminders.booking.ConversationState;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.reminder.Reminder;
import app.clinicreminders.reminder.ReminderRepository;
import app.clinicreminders.reminder.ReminderStatus;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.staff.StaffPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PatientsPageIT extends DashboardTest {

    @Autowired
    AppointmentService appointmentService;

    @Autowired
    AppointmentRepository appointments;

    @Autowired
    PatientRepository patients;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    ReminderRepository reminders;

    private Clinic clinic;
    private Patient rana;
    private StaffPrincipal staff;

    @BeforeEach
    void clinicWithPatients() {
        clinic = data.connectedClinic("A", "pn-a");
        rana = data.patient(clinic, "Rana Haddad", "+96170123456", Language.AR);
        data.patient(clinic, "Karim", "+9613111222", Language.EN);
        Clinic other = data.clinic("B");
        data.patient(other, "Rania Elsewhere", "+96170123457", Language.AR);
        staff = secretaryOf(clinic);
    }

    @Test
    void eachPatientShowsWhetherTheyAreBooked() {
        Doctor doctor = data.doctor(clinic, "Dr. Khoury", 30);
        // Wednesday 21 October 10:30 in Beirut; a cancelled and a past one do not count.
        data.appointment(doctor, rana, Instant.parse("2026-10-21T07:30:00Z"), Duration.ofMinutes(30));
        data.appointment(doctor, rana, Instant.parse("2026-10-28T07:30:00Z"), Duration.ofMinutes(30));
        data.appointment(doctor, rana, Instant.parse("2026-10-01T07:30:00Z"), Duration.ofMinutes(30));
        Patient karim = patients.findByClinicIdAndPhoneE164OrderById(clinic.getId(), "+9613111222").getFirst();
        data.cancelledAppointment(doctor, karim, Instant.parse("2026-10-22T07:30:00Z"), Duration.ofMinutes(30));

        assertThat(get(staff, "/patients")).hasStatusOk().bodyText()
                .contains("Next appointment", "Wed 21 Oct, 10:30 AM", "Dr. Khoury", "No upcoming appointment")
                .doesNotContain("Wed 28 Oct", "Thu 22 Oct");
    }

    @Test
    void searchesByNameOrByPhoneAsTyped() {
        assertThat(get(staff, "/patients?q=ran")).hasStatusOk().bodyText()
                .contains("Rana Haddad").doesNotContain("Karim", "Rania Elsewhere");
        assertThat(get(staff, "/patients?q=70 123")).bodyText()
                .contains("Rana Haddad").doesNotContain("Rania Elsewhere");
        assertThat(get(staff, "/patients?q=03 111")).bodyText()
                .contains("Karim").doesNotContain("Rana Haddad");
    }

    @Test
    void deletingAPatientCancelsTheFutureKeepsThePastAndForgetsTheConversation() {
        Doctor doctor = data.doctor(clinic, 30);
        Appointment past = data.appointment(doctor, rana, Instant.parse("2026-10-01T07:00:00Z"), Duration.ofMinutes(30));
        Appointment future = appointmentService.book(clinic.getId(), doctor.getId(), rana.getId(),
                Instant.parse("2026-11-03T08:30:00Z"), AppointmentSource.WHATSAPP);
        conversations.save(new Conversation(clinic.getId(), rana.getPhoneE164(), ConversationState.MENU,
                Instant.parse("2026-10-14T09:30:00Z")));

        assertThat(post(staff, "/patients/{id}/delete", rana.getId()))
                .hasStatus3xxRedirection().headers().hasValue("Location", "/patients");

        assertThat(patients.findByIdAndClinicId(rana.getId(), clinic.getId())).isEmpty();
        Appointment cancelled = appointments.findByIdAndClinicId(future.getId(), clinic.getId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(cancelled.getPatientId()).isNull();
        assertThat(reminders.findByClinicIdAndAppointmentIdOrderByDueAt(clinic.getId(), future.getId()))
                .extracting(Reminder::getStatus).doesNotContain(ReminderStatus.PENDING);
        Map<String, Object> kept = jdbc.queryForMap("select status, patient_id from appointment where id = ?", past.getId());
        assertThat(kept.get("status")).isEqualTo("BOOKED");
        assertThat(kept.get("patient_id")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from conversation", Integer.class)).isZero();
    }

    @Test
    void theListLoadsThirtyAtATimeAsYouScroll() {
        for (int i = 0; i < 30; i++) {
            data.patient(clinic, "Zed " + (100 + i), "+9617099" + (1000 + i), Language.AR);
        }

        assertThat(get(staff, "/patients")).bodyText()
                .contains("32 patients", "class=\"load-more\"", "hx-get=\"/patients?q=&amp;page=2\"")
                .doesNotContain("Zed 128", "Zed 129");
        assertThat(get(staff, "/patients?page=2").header("HX-Request", "true")).hasStatusOk().bodyText()
                .as("only the next cards, to append").contains("Zed 128", "Zed 129").doesNotContain("<html", "class=\"load-more\"");
        assertThat(get(staff, "/patients?q=zed")).bodyText().as("thirty fit on one page")
                .contains("30 patients", "Zed 129").doesNotContain("class=\"load-more\"", "Rana Haddad");
        data.patient(clinic, "Zed 130", "+961709992000", Language.AR);
        assertThat(get(staff, "/patients?q=zed")).bodyText().as("the search goes with the next page")
                .contains("hx-get=\"/patients?q=zed&amp;page=2\"");
        assertThat(get(staff, "/patients?q=rana")).bodyText().contains("1 patients").doesNotContain("class=\"load-more\"");
    }

    @Test
    void anotherClinicsPatientCannotBeDeleted() {
        Patient theirs = patients.findByClinicIdAndPhoneE164OrderById(
                jdbc.queryForObject("select id from clinic where name = 'B'", Long.class), "+96170123457").getFirst();

        assertThat(post(staff, "/patients/{id}/delete", theirs.getId())).hasStatus(404);
        assertThat(jdbc.queryForObject("select count(*) from patient", Integer.class)).isEqualTo(3);
    }
}
