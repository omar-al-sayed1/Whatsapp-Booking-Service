package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.scheduling.ScheduleRule;
import app.clinicreminders.scheduling.SlotService;
import app.clinicreminders.staff.StaffPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DoctorsPageIT extends DashboardTest {

    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 21);

    @Autowired
    ClinicRepository clinics;

    @Autowired
    DoctorRepository doctors;

    @Autowired
    SlotService slots;

    private Clinic clinic;
    private Doctor doctor;
    private StaffPrincipal staff;

    @BeforeEach
    void clinicWithDoctor() {
        clinic = data.connectedClinic("A", "pn-a");
        doctor = data.doctor(clinic, "Dr. Khoury", 30);
        staff = secretaryOf(clinic);
    }

    @Test
    void listsDoctorsWithTheirWorkingDaysAndNoClinicSettings() {
        data.weekly(doctor, DayOfWeek.MONDAY, "09:00", "13:00");

        assertThat(get(staff, "/doctors")).hasStatusOk().bodyText()
                .contains("<title>Doctors</title>", "<h1>Doctors</h1>", "Dr. Khoury", "class=\"on\"",
                        "/doctors/" + doctor.getId(), "Add a doctor")
                .doesNotContain("name=\"h24Enabled\"", "class=\"tabs\"");
    }

    @Test
    void addsAndEditsDoctorsWhoTakeTheClinicsAppointmentLength() {
        clinic.changeSlotMinutes(25);
        clinics.save(clinic);
        assertThat(post(staff, "/doctors").param("displayName", "Dr. Saab")).hasStatus3xxRedirection();
        Doctor saab = doctors.findByClinicIdOrderByDisplayName(clinic.getId()).stream()
                .filter(d -> d.getDisplayName().equals("Dr. Saab")).findFirst().orElseThrow();
        assertThat(saab.getSlotMinutes()).isEqualTo(25);

        assertThat(post(staff, "/doctors/{id}", saab.getId()).param("displayName", "Dr. Saab Jr").param("slotMinutes", "15"))
                .hasRedirectedUrl("/doctors/" + saab.getId());

        Doctor edited = doctors.findByIdAndClinicId(saab.getId(), clinic.getId()).orElseThrow();
        assertThat(edited.getDisplayName()).isEqualTo("Dr. Saab Jr");
        assertThat(edited.getSlotMinutes()).as("only the operator sets the length").isEqualTo(25);
        assertThat(edited.isActive()).isFalse();
        assertThat(get(staff, "/doctors/{id}", saab.getId())).bodyText()
                .contains("Appointments are 25 minutes long.").doesNotContain("name=\"slotMinutes\"");
    }

    @Test
    void refusesADoctorWithoutAName() {
        assertThat(post(staff, "/doctors").param("displayName", " "))
                .hasRedirectedUrl("/doctors").flash().containsEntry("error", "error.doctor");
        assertThat(post(staff, "/doctors/{id}", doctor.getId()).param("displayName", " "))
                .flash().containsEntry("error", "error.doctor");
    }

    @Test
    void weeklyHoursFeedTheSlotEngine() {
        post(staff, "/doctors/{id}/hours", doctor.getId())
                .param("day", "WEDNESDAY").param("start", "09:00").param("end", "10:00").exchange();
        assertThat(slots.freeSlots(clinic.getId(), doctor.getId(), WEDNESDAY, WEDNESDAY)).hasSize(2);

        Long ruleId = jdbc.queryForObject("select id from schedule_rule", Long.class);
        assertThat(post(staff, "/doctors/{id}/hours/{rule}/delete", doctor.getId(), ruleId))
                .hasRedirectedUrl("/doctors/" + doctor.getId());

        assertThat(slots.freeSlots(clinic.getId(), doctor.getId(), WEDNESDAY, WEDNESDAY)).isEmpty();
    }

    @Test
    void hoursThatEndBeforeTheyStartAreRefused() {
        assertThat(post(staff, "/doctors/{id}/hours", doctor.getId())
                .param("day", "MONDAY").param("start", "13:00").param("end", "09:00"))
                .flash().containsEntry("error", "error.hours");
        assertThat(post(staff, "/doctors/{id}/days", doctor.getId())
                .param("date", WEDNESDAY.toString()).param("closed", "false").param("start", "09:00"))
                .flash().containsEntry("error", "error.hours");
    }

    @Test
    void aDayOffOrSpecialHoursReplaceEarlierOnes() {
        data.weekly(doctor, DayOfWeek.WEDNESDAY, "09:00", "11:00");

        post(staff, "/doctors/{id}/days", doctor.getId()).param("date", WEDNESDAY.toString()).param("closed", "true")
                .exchange();
        assertThat(slots.freeSlots(clinic.getId(), doctor.getId(), WEDNESDAY, WEDNESDAY)).isEmpty();

        post(staff, "/doctors/{id}/days", doctor.getId()).param("date", WEDNESDAY.toString())
                .param("start", "09:00").param("end", "09:30").exchange();
        assertThat(slots.freeSlots(clinic.getId(), doctor.getId(), WEDNESDAY, WEDNESDAY)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from schedule_exception", Integer.class)).isEqualTo(1);

        Long dayId = jdbc.queryForObject("select id from schedule_exception", Long.class);
        post(staff, "/doctors/{id}/days/{day}/delete", doctor.getId(), dayId).exchange();
        assertThat(slots.freeSlots(clinic.getId(), doctor.getId(), WEDNESDAY, WEDNESDAY)).hasSize(4);
    }

    @Test
    void openingHoursAreDrawnOnASixToTenBar() {
        data.weekly(doctor, DayOfWeek.MONDAY, "09:00", "13:00");
        data.weekly(doctor, DayOfWeek.TUESDAY, "05:00", "23:30");

        assertThat(get(staff, "/doctors/{id}", doctor.getId())).bodyText()
                .as("9 to 13: 3 h after 6:00 for 4 h of 16").contains("inset-inline-start:18.75%;width:25.00%")
                .as("clamped to the bar").contains("inset-inline-start:0.00%;width:100.00%");
    }

    @Test
    void theDoctorPageListsHoursAndDaysOffAndLeadsBackToDoctors() {
        data.weekly(doctor, DayOfWeek.MONDAY, "09:00", "13:00");
        data.closedOn(doctor, LocalDate.of(2026, 12, 25));

        assertThat(get(staff, "/doctors/{id}", doctor.getId())).hasStatusOk().bodyText()
                .contains("Monday", "9:00 AM – 1:00 PM", "Friday 25 December 2026", "Closed all day")
                .contains("class=\"back-link\" href=\"/doctors\"")
                .doesNotContain("/settings/");
    }

    // --- Deleting ---

    @Test
    void aDeletedDoctorLeavesTheClinicWithTheirHoursAndLogin() {
        data.weekly(doctor, DayOfWeek.WEDNESDAY, "09:00", "11:00");
        data.closedOn(doctor, WEDNESDAY);
        doctorLoginOf(doctor);
        Doctor other = data.doctor(clinic, "Dr. Saab", 30);
        assertThat(get(staff, "/doctors/{id}", doctor.getId())).bodyText()
                .contains("data-confirm=\"Delete Dr. Khoury? Their hours and login are removed", "Delete doctor");

        assertThat(post(staff, "/doctors/{id}/delete", doctor.getId()))
                .hasRedirectedUrl("/doctors").flash().containsEntry("notice", "notice.doctor-deleted");

        assertThat(doctors.findByIdAndClinicId(doctor.getId(), clinic.getId()).orElseThrow().isDeleted())
                .as("kept for the history of past appointments").isTrue();
        assertThat(get(staff, "/doctors")).bodyText().contains("Dr. Saab").doesNotContain("Dr. Khoury");
        assertThat(get(staff, "/doctors/{id}", doctor.getId())).hasStatus(404);
        assertThat(get(staff, "/appointments/new")).bodyText().doesNotContain("Dr. Khoury");
        assertThat(get(staff, "/week?doctorId={id}", doctor.getId())).bodyText().doesNotContain("Dr. Khoury");
        assertThat(jdbc.queryForObject("select count(*) from schedule_rule where doctor_id = ?", Integer.class, doctor.getId()))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from schedule_exception where doctor_id = ?", Integer.class,
                doctor.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from staff_user where doctor_id = ?", Integer.class, doctor.getId()))
                .isZero();
        assertThat(post(staff, "/doctors/{id}/hours", doctor.getId())
                .param("day", "MONDAY").param("start", "09:00").param("end", "10:00")).hasStatus(404);
        assertThat(other.isActive()).isTrue();
    }

    @Test
    void aDoctorWithAppointmentsToComeIsNotDeleted() {
        data.appointment(doctor, data.patient(clinic), Instant.parse("2026-10-21T06:00:00Z"), Duration.ofMinutes(30));

        assertThat(get(staff, "/doctors/{id}", doctor.getId())).bodyText()
                .contains("Upcoming appointments: 1.", "/week?doctorId=" + doctor.getId())
                .doesNotContain("/doctors/" + doctor.getId() + "/delete");
        assertThat(post(staff, "/doctors/{id}/delete", doctor.getId()))
                .hasRedirectedUrl("/doctors/" + doctor.getId()).flash().containsEntry("error", "error.doctor-has-appointments");
        assertThat(doctors.findByIdAndClinicId(doctor.getId(), clinic.getId()).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    void pastAppointmentsOfADeletedDoctorStayInTheHistory() {
        Patient rana = data.patient(clinic, "Rana Haddad", "+96170123456", Language.AR);
        // Wednesday 7 October, last week.
        data.appointment(doctor, rana, Instant.parse("2026-10-07T06:00:00Z"), Duration.ofMinutes(30));

        post(staff, "/doctors/{id}/delete", doctor.getId()).exchange();

        assertThat(get(staff, "/?date=2026-10-07")).bodyText().contains("Dr. Khoury", "Rana Haddad");
        assertThat(get(staff, "/")).bodyText().as("not on a day without their appointments").doesNotContain("Dr. Khoury");
    }

    @Test
    void onlyTheFrontDeskDeletesAndOnlyTheirClinicsDoctors() {
        Doctor theirs = data.doctor(data.clinic("B"), "Dr. Elsewhere", 30);

        assertThat(post(doctorLoginOf(doctor), "/doctors/{id}/delete", doctor.getId())).hasStatus(403);
        assertThat(post(staff, "/doctors/{id}/delete", theirs.getId())).hasStatus(404);
        assertThat(doctors.findByIdAndClinicId(theirs.getId(), theirs.getClinicId()).orElseThrow().isDeleted()).isFalse();
        assertThat(doctors.findByIdAndClinicId(doctor.getId(), clinic.getId()).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    void anotherClinicsDoctorsHoursAndDaysAreOffLimits() {
        Clinic other = data.clinic("B");
        Doctor theirs = data.doctor(other, "Dr. Elsewhere", 30);
        ScheduleRule theirRule = data.weekly(theirs, DayOfWeek.MONDAY, "09:00", "10:00");
        Long theirDay = data.closedOn(theirs, WEDNESDAY).getId();

        assertThat(get(staff, "/doctors/{id}", theirs.getId())).hasStatus(404);
        assertThat(post(staff, "/doctors/{id}", theirs.getId()).param("displayName", "Hacked")).hasStatus(404);
        assertThat(post(staff, "/doctors/{id}/hours", theirs.getId())
                .param("day", "MONDAY").param("start", "09:00").param("end", "10:00")).hasStatus(404);
        assertThat(post(staff, "/doctors/{id}/hours/{rule}/delete", doctor.getId(), theirRule.getId())).hasStatus(404);
        assertThat(post(staff, "/doctors/{id}/days/{day}/delete", doctor.getId(), theirDay)).hasStatus(404);
        assertThat(doctors.findByIdAndClinicId(theirs.getId(), other.getId()).orElseThrow().getDisplayName())
                .isEqualTo("Dr. Elsewhere");
        assertThat(jdbc.queryForObject("select count(*) from schedule_rule", Integer.class)).isEqualTo(1);
    }
}
