package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentStatus;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.scheduling.ScheduleRule;
import app.clinicreminders.staff.StaffPrincipal;
import app.clinicreminders.staff.StaffRole;
import app.clinicreminders.staff.StaffUser;
import app.clinicreminders.staff.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Doctor logins: made by the front desk, they see and change only their own doctor's appointments,
 * patients and hours. Today is Wednesday 14 October 2026 (12:00 in Beirut, UTC+3).
 */
class DoctorLoginsIT extends DashboardTest {

    /** Wednesday 21 October 2026, 09:00 in Beirut. */
    private static final Instant NEXT_WEDNESDAY_NINE = Instant.parse("2026-10-21T06:00:00Z");

    @Autowired
    StaffUserRepository users;

    @Autowired
    DoctorRepository doctors;

    @Autowired
    AppointmentRepository appointments;

    private Clinic clinic;
    private Doctor khoury;
    private Doctor saab;
    private StaffPrincipal secretary;
    private StaffPrincipal khouryLogin;
    private Patient rana;
    private Patient karim;
    private Appointment ranaWithKhoury;
    private Appointment karimWithSaab;

    @BeforeEach
    void twoDoctorsWithOnePatientEach() {
        clinic = data.connectedClinic("A", "pn-a");
        khoury = data.doctor(clinic, "Dr. Khoury", 30);
        saab = data.doctor(clinic, "Dr. Saab", 30);
        data.weekly(khoury, DayOfWeek.WEDNESDAY, "09:00", "12:00");
        data.weekly(saab, DayOfWeek.WEDNESDAY, "09:00", "12:00");
        secretary = secretaryOf(clinic);
        khouryLogin = doctorLoginOf(khoury);
        rana = data.patient(clinic, "Rana Haddad", "+96170123456", Language.AR);
        karim = data.patient(clinic, "Karim Saleh", "+96171222333", Language.EN);
        ranaWithKhoury = data.appointment(khoury, rana, NEXT_WEDNESDAY_NINE, Duration.ofMinutes(30));
        karimWithSaab = data.appointment(saab, karim, NEXT_WEDNESDAY_NINE, Duration.ofMinutes(30));
    }

    // --- Making the login ---

    @Test
    void theFrontDeskAddsADoctorWithALoginThatWorks() {
        assertThat(post(secretary, "/doctors").param("displayName", "Dr. Nassar").param("addLogin", "true")
                .param("username", "dr.nassar").param("password", "long-enough-1"))
                .hasStatus3xxRedirection().flash().containsEntry("notice", "notice.doctor-login-added");

        StaffUser login = users.findByUsernameIgnoreCase("dr.nassar").orElseThrow();
        Doctor nassar = doctors.findByIdAndClinicId(login.getDoctorId(), clinic.getId()).orElseThrow();
        assertThat(nassar.getDisplayName()).isEqualTo("Dr. Nassar");
        assertThat(login.getRole()).isEqualTo(StaffRole.DOCTOR);
        assertThat(login.getClinicId()).isEqualTo(clinic.getId());
        assertThat(logIn("dr.nassar", "long-enough-1")).isEqualTo("/");
    }

    @Test
    void aRefusedLoginAddsNoDoctorEither() {
        users.save(StaffUser.clinicStaff(clinic.getId(), "taken.name", "{noop}x", StaffRole.SECRETARY, Language.EN));

        assertThat(post(secretary, "/doctors").param("displayName", "Dr. Nassar").param("addLogin", "true")
                .param("username", "taken.name").param("password", "long-enough-1"))
                .flash().containsEntry("error", "error.username-taken").containsEntry("draftName", "Dr. Nassar");
        assertThat(post(secretary, "/doctors").param("displayName", "Dr. Nassar").param("addLogin", "true")
                .param("username", "dr.nassar").param("password", "short"))
                .flash().containsEntry("error", "error.password-short");
        assertThat(post(secretary, "/doctors").param("displayName", "Dr. Nassar").param("addLogin", "true")
                .param("username", "no spaces").param("password", "long-enough-1"))
                .flash().containsEntry("error", "error.username");

        assertThat(doctors.findByClinicIdOrderByDisplayName(clinic.getId())).extracting(Doctor::getDisplayName)
                .doesNotContain("Dr. Nassar");
    }

    @Test
    void anExistingDoctorGetsALoginANewPasswordAndCanBeDisabled() {
        assertThat(get(secretary, "/doctors/{id}", saab.getId())).bodyText().contains("No login yet.", "Add a login");

        post(secretary, "/doctors/{id}/login", saab.getId()).param("username", "dr.saab").param("password", "first-password")
                .exchange();
        assertThat(logIn("dr.saab", "first-password")).isEqualTo("/");
        assertThat(get(secretary, "/doctors/{id}", saab.getId())).bodyText().contains("dr.saab", "Set the password")
                .doesNotContain("No login yet.");

        assertThat(post(secretary, "/doctors/{id}/login/password", saab.getId()).param("password", "second-password"))
                .flash().containsEntry("notice", "notice.doctor-password-set");
        assertThat(logIn("dr.saab", "first-password")).isEqualTo("/login?error");
        assertThat(logIn("dr.saab", "second-password")).isEqualTo("/");

        post(secretary, "/doctors/{id}/login/enabled", saab.getId()).param("enabled", "false").exchange();
        assertThat(logIn("dr.saab", "second-password")).isEqualTo("/login?error");
        assertThat(post(secretary, "/doctors/{id}/login", saab.getId()).param("username", "dr.saab2")
                .param("password", "another-password")).flash().containsEntry("error", "error.not-allowed");
    }

    @Test
    void aLoginBelongsToOneDoctorOfItsOwnClinic() {
        Doctor elsewhere = data.doctor(data.clinic("B"), "Dr. Elsewhere", 30);

        assertThatThrownBy(() -> users.save(StaffUser.doctor(clinic.getId(), khoury.getId(), "second", "{noop}x", Language.EN)))
                .as("one login per doctor").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> users.save(StaffUser.doctor(clinic.getId(), elsewhere.getId(), "cross", "{noop}x", Language.EN)))
                .as("not another clinic's doctor").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into staff_user (clinic_id, username, password_hash, role, language) values (?, 'nodoc', 'x', 'DOCTOR', 'en')""",
                clinic.getId())).as("a doctor's login needs its doctor").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> StaffUser.clinicStaff(clinic.getId(), "x", "x", StaffRole.DOCTOR, Language.EN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theOperatorCannotMakeADoctorLoginWithoutADoctor() {
        StaffPrincipal operator = StaffPrincipal.of(users.save(StaffUser.operator("op1", "{noop}x", Language.EN)));

        assertThat(post(operator, "/operator/clinics/{id}/logins", clinic.getId()).param("username", "dr.x")
                .param("role", "DOCTOR").param("language", "en")).flash().containsEntry("error", "error.username");
        assertThat(users.findByUsernameIgnoreCase("dr.x")).isEmpty();
    }

    // --- What the doctor sees ---

    @Test
    void theDoctorSeesOnlyTheirOwnDayWeekAndPatients() {
        assertThat(get(khouryLogin, "/?date=2026-10-21")).hasStatusOk().bodyText()
                .contains("Dr. Khoury", "Rana Haddad").doesNotContain("Dr. Saab", "Karim Saleh");
        assertThat(get(khouryLogin, "/week?doctorId={id}&start=2026-10-21", saab.getId())).hasStatusOk().bodyText()
                .as("another doctor's week falls back to their own").contains("Rana Haddad").doesNotContain("Karim Saleh", "Dr. Saab");
        assertThat(get(khouryLogin, "/patients")).bodyText().contains("Rana Haddad").doesNotContain("Karim Saleh");
        assertThat(get(khouryLogin, "/patients?q=karim")).bodyText().doesNotContain("Karim Saleh");
        assertThat(get(khouryLogin, "/appointments/patients")).bodyText().contains("Rana Haddad").doesNotContain("Karim Saleh");
    }

    @Test
    void theDoctorsMenuIsTheirOwnDay() {
        assertThat(get(khouryLogin, "/")).bodyText()
                .contains("My hours", "href=\"/doctors/" + khoury.getId() + "\"")
                .doesNotContain("href=\"/settings\"", "href=\"/doctors\"", "class=\"bell");
        assertThat(get(khouryLogin, "/doctors")).hasRedirectedUrl("/doctors/" + khoury.getId());
        assertThat(get(khouryLogin, "/doctors/{id}", khoury.getId())).hasStatusOk().bodyText()
                .contains("Weekly hours", "Days off").doesNotContain("Profile", "Add a login", "name=\"displayName\"");
    }

    @Test
    void theFrontDesksPagesAndActionsAreClosedToDoctors() {
        assertThat(get(khouryLogin, "/settings")).hasStatus(403);
        assertThat(get(khouryLogin, "/attention")).hasStatus(403);
        assertThat(post(khouryLogin, "/doctors").param("displayName", "Dr. Me Too")).hasStatus(403);
        assertThat(post(khouryLogin, "/doctors/{id}", khoury.getId()).param("displayName", "Renamed")).hasStatus(403);
        assertThat(post(khouryLogin, "/doctors/{id}/login/password", khoury.getId()).param("password", "long-enough-1"))
                .hasStatus(403);
        assertThat(post(khouryLogin, "/patients/{id}/delete", rana.getId())).hasStatus(403);
        assertThat(get(khouryLogin, "/patients")).bodyText().doesNotContain("/delete");
    }

    // --- What the doctor can change ---

    @Test
    void theDoctorBooksForThemselfOnly() {
        assertThat(post(khouryLogin, "/appointments").param("doctorId", khoury.getId().toString())
                .param("date", "2026-10-21").param("time", "10:00").param("phone", "70999888").param("name", "Nour")
                .param("language", "ar")).hasStatus3xxRedirection();
        assertThat(post(khouryLogin, "/appointments").param("doctorId", saab.getId().toString())
                .param("date", "2026-10-21").param("time", "10:00").param("phone", "70999777").param("name", "Ziad")
                .param("language", "ar")).hasStatus(404);
        assertThat(get(khouryLogin, "/appointments/slots?doctorId={id}&date=2026-10-21", saab.getId())).hasStatus(404);
        assertThat(get(khouryLogin, "/appointments/new")).bodyText()
                .contains("Dr. Khoury").doesNotContain("Dr. Saab");
        assertThat(jdbc.queryForObject("select count(*) from appointment where doctor_id = ?", Integer.class, saab.getId()))
                .isEqualTo(1);
    }

    @Test
    void anotherDoctorsAppointmentDoesNotExistForTheDoctor() {
        long theirs = karimWithSaab.getId();

        assertThat(get(khouryLogin, "/appointments/{id}", theirs)).hasStatus(404);
        assertThat(post(khouryLogin, "/appointments/{id}/cancel", theirs)).hasStatus(404);
        assertThat(post(khouryLogin, "/appointments/{id}/arrived", theirs)).hasStatus(404);
        assertThat(get(khouryLogin, "/appointments/{id}/reschedule", theirs)).hasStatus(404);
        assertThat(post(khouryLogin, "/week/move").param("appointmentId", String.valueOf(theirs))
                .param("date", "2026-10-21").param("time", "11:00")).hasStatus(404);
        assertThat(appointments.findByIdAndClinicId(theirs, clinic.getId()).orElseThrow().getStatus())
                .isEqualTo(AppointmentStatus.BOOKED);

        assertThat(get(khouryLogin, "/appointments/{id}", ranaWithKhoury.getId())).hasStatusOk();
        assertThat(post(khouryLogin, "/appointments/{id}/cancel", ranaWithKhoury.getId())).hasStatus3xxRedirection();
        assertThat(appointments.findByIdAndClinicId(ranaWithKhoury.getId(), clinic.getId()).orElseThrow().getStatus())
                .isEqualTo(AppointmentStatus.CANCELLED);
    }

    @Test
    void theDoctorChangesTheirOwnHoursOnly() {
        assertThat(post(khouryLogin, "/doctors/{id}/hours", khoury.getId())
                .param("day", "MONDAY").param("start", "15:00").param("end", "18:00"))
                .hasRedirectedUrl("/doctors/" + khoury.getId());
        assertThat(jdbc.queryForObject("select count(*) from schedule_rule where doctor_id = ?", Integer.class, khoury.getId()))
                .isEqualTo(2);

        ScheduleRule saabsHours = data.weekly(saab, DayOfWeek.MONDAY, "09:00", "10:00");
        assertThat(post(khouryLogin, "/doctors/{id}/hours", saab.getId())
                .param("day", "MONDAY").param("start", "15:00").param("end", "18:00")).hasStatus(404);
        assertThat(post(khouryLogin, "/doctors/{id}/hours/{rule}/delete", khoury.getId(), saabsHours.getId()))
                .as("through their own page either").hasStatus(404);
        assertThat(post(khouryLogin, "/doctors/{id}/days", saab.getId()).param("date", "2026-10-28").param("closed", "true"))
                .hasStatus(404);
        assertThat(get(khouryLogin, "/doctors/{id}", saab.getId())).hasStatus(404);
        assertThat(post(khouryLogin, "/week/open-day").param("doctorId", saab.getId().toString()).param("date", "2026-10-25")
                .param("start", "09:00").param("end", "10:00")).hasStatus(404);
        assertThat(jdbc.queryForObject("select count(*) from schedule_rule where doctor_id = ?", Integer.class, saab.getId()))
                .isEqualTo(2);
    }

    private String logIn(String username, String password) {
        return mvc.post().uri("/login").param("username", username).param("password", password).with(csrf())
                .exchange().getResponse().getHeader("Location");
    }
}
