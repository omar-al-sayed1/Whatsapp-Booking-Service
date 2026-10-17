package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.Language;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.scheduling.ScheduleRule;
import app.clinicreminders.scheduling.SlotService;
import app.clinicreminders.staff.StaffPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SettingsPageIT extends DashboardTest {

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
    void savesTheClinicsLanguageAndReminderSwitches() {
        assertThat(post(staff, "/settings/clinic").param("defaultLanguage", "en").param("h24Enabled", "true"))
                .hasStatus3xxRedirection().flash().containsEntry("notice", "notice.saved");

        Clinic saved = clinics.findById(clinic.getId()).orElseThrow();
        assertThat(saved.getDefaultLanguage()).isEqualTo(Language.EN);
        assertThat(saved.isReminderH24Enabled()).isTrue();
        assertThat(saved.isReminderH2Enabled()).isFalse();
    }

    @Test
    void savesTheAddressAndHoursPatientsGetFromInfo() {
        post(staff, "/settings/clinic").param("defaultLanguage", "ar")
                .param("address", "  شارع البرازيل، زحلة ").param("hoursText", "Mon-Sat 9-1").exchange();

        Clinic saved = clinics.findById(clinic.getId()).orElseThrow();
        assertThat(saved.getAddress()).isEqualTo("شارع البرازيل، زحلة");
        assertThat(saved.getHoursText()).isEqualTo("Mon-Sat 9-1");
        assertThat(get(staff, "/settings")).bodyText().contains("شارع البرازيل، زحلة", "Mon-Sat 9-1");

        post(staff, "/settings/clinic").param("defaultLanguage", "ar").param("address", " ").exchange();

        Clinic cleared = clinics.findById(clinic.getId()).orElseThrow();
        assertThat(cleared.getAddress()).isNull();
        assertThat(cleared.getHoursText()).isNull();
    }

    @Test
    void tooLongAContactTextSavesNothing() {
        assertThat(post(staff, "/settings/clinic").param("defaultLanguage", "en").param("h24Enabled", "true")
                .param("address", "x".repeat(Clinic.CONTACT_TEXT_MAX + 1)))
                .flash().containsEntry("error", "error.contact");

        Clinic unchanged = clinics.findById(clinic.getId()).orElseThrow();
        assertThat(unchanged.getAddress()).isNull();
        assertThat(unchanged.getDefaultLanguage()).isEqualTo(Language.AR);
    }

    @Test
    void addsAndEditsDoctorsWhoTakeTheClinicsAppointmentLength() {
        clinic.changeSlotMinutes(25);
        clinics.save(clinic);
        assertThat(post(staff, "/settings/doctors").param("displayName", "Dr. Saab")).hasStatus3xxRedirection();
        Doctor saab = doctors.findByClinicIdOrderByDisplayName(clinic.getId()).stream()
                .filter(d -> d.getDisplayName().equals("Dr. Saab")).findFirst().orElseThrow();
        assertThat(saab.getSlotMinutes()).isEqualTo(25);

        post(staff, "/settings/doctors/{id}", saab.getId()).param("displayName", "Dr. Saab Jr")
                .param("slotMinutes", "15").exchange();

        Doctor edited = doctors.findByIdAndClinicId(saab.getId(), clinic.getId()).orElseThrow();
        assertThat(edited.getDisplayName()).isEqualTo("Dr. Saab Jr");
        assertThat(edited.getSlotMinutes()).as("only the operator sets the length").isEqualTo(25);
        assertThat(edited.isActive()).isFalse();
        assertThat(get(staff, "/settings/doctors/{id}", saab.getId())).bodyText()
                .contains("Appointments are 25 minutes long.").doesNotContain("name=\"slotMinutes\"");
    }

    @Test
    void refusesADoctorWithoutAName() {
        assertThat(post(staff, "/settings/doctors").param("displayName", " "))
                .flash().containsEntry("error", "error.doctor");
        assertThat(post(staff, "/settings/doctors/{id}", doctor.getId()).param("displayName", " "))
                .flash().containsEntry("error", "error.doctor");
    }

    @Test
    void weeklyHoursFeedTheSlotEngine() {
        post(staff, "/settings/doctors/{id}/hours", doctor.getId())
                .param("day", "WEDNESDAY").param("start", "09:00").param("end", "10:00").exchange();
        assertThat(slots.freeSlots(clinic.getId(), doctor.getId(), WEDNESDAY, WEDNESDAY)).hasSize(2);

        Long ruleId = jdbc.queryForObject("select id from schedule_rule", Long.class);
        post(staff, "/settings/hours/{id}/delete", ruleId).param("doctorId", doctor.getId().toString()).exchange();

        assertThat(slots.freeSlots(clinic.getId(), doctor.getId(), WEDNESDAY, WEDNESDAY)).isEmpty();
    }

    @Test
    void hoursThatEndBeforeTheyStartAreRefused() {
        assertThat(post(staff, "/settings/doctors/{id}/hours", doctor.getId())
                .param("day", "MONDAY").param("start", "13:00").param("end", "09:00"))
                .flash().containsEntry("error", "error.hours");
        assertThat(post(staff, "/settings/doctors/{id}/days", doctor.getId())
                .param("date", WEDNESDAY.toString()).param("closed", "false").param("start", "09:00"))
                .flash().containsEntry("error", "error.hours");
    }

    @Test
    void aDayOffOrSpecialHoursReplaceEarlierOnes() {
        data.weekly(doctor, DayOfWeek.WEDNESDAY, "09:00", "11:00");

        post(staff, "/settings/doctors/{id}/days", doctor.getId()).param("date", WEDNESDAY.toString()).param("closed", "true")
                .exchange();
        assertThat(slots.freeSlots(clinic.getId(), doctor.getId(), WEDNESDAY, WEDNESDAY)).isEmpty();

        post(staff, "/settings/doctors/{id}/days", doctor.getId()).param("date", WEDNESDAY.toString())
                .param("start", "09:00").param("end", "09:30").exchange();
        assertThat(slots.freeSlots(clinic.getId(), doctor.getId(), WEDNESDAY, WEDNESDAY)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from schedule_exception", Integer.class)).isEqualTo(1);

        Long dayId = jdbc.queryForObject("select id from schedule_exception", Long.class);
        post(staff, "/settings/days/{id}/delete", dayId).param("doctorId", doctor.getId().toString()).exchange();
        assertThat(slots.freeSlots(clinic.getId(), doctor.getId(), WEDNESDAY, WEDNESDAY)).hasSize(4);
    }

    @Test
    void openingHoursAreDrawnOnASixToTenBar() {
        data.weekly(doctor, DayOfWeek.MONDAY, "09:00", "13:00");
        data.weekly(doctor, DayOfWeek.TUESDAY, "05:00", "23:30");

        assertThat(get(staff, "/settings/doctors/{id}", doctor.getId())).bodyText()
                .as("9 to 13: 3 h after 6:00 for 4 h of 16").contains("inset-inline-start:18.75%;width:25.00%")
                .as("clamped to the bar").contains("inset-inline-start:0.00%;width:100.00%");
    }

    @Test
    void theDoctorsTabListsDoctorsWithTheirWorkingDays() {
        data.weekly(doctor, DayOfWeek.MONDAY, "09:00", "13:00");

        assertThat(get(staff, "/settings?tab=doctors")).hasStatusOk().bodyText()
                .contains("Dr. Khoury", "class=\"on\"", "/settings/doctors/" + doctor.getId())
                .doesNotContain("name=\"h24Enabled\"");
        assertThat(get(staff, "/settings")).bodyText().contains("name=\"h24Enabled\"");
    }

    @Test
    void theDoctorPageListsHoursAndDaysOff() {
        data.weekly(doctor, DayOfWeek.MONDAY, "09:00", "13:00");
        data.closedOn(doctor, LocalDate.of(2026, 12, 25));

        assertThat(get(staff, "/settings/doctors/{id}", doctor.getId())).hasStatusOk().bodyText()
                .contains("Monday", "9:00 AM – 1:00 PM", "Friday 25 December 2026", "Closed all day");
    }

    @Test
    void anotherClinicsDoctorsHoursAndDaysAreOffLimits() {
        Clinic other = data.clinic("B");
        Doctor theirs = data.doctor(other, "Dr. Elsewhere", 30);
        ScheduleRule theirRule = data.weekly(theirs, DayOfWeek.MONDAY, "09:00", "10:00");
        Long theirDay = data.closedOn(theirs, WEDNESDAY).getId();

        assertThat(get(staff, "/settings/doctors/{id}", theirs.getId())).hasStatus(404);
        assertThat(post(staff, "/settings/doctors/{id}", theirs.getId()).param("displayName", "Hacked"))
                .hasStatus(404);
        assertThat(post(staff, "/settings/doctors/{id}/hours", theirs.getId())
                .param("day", "MONDAY").param("start", "09:00").param("end", "10:00")).hasStatus(404);
        assertThat(post(staff, "/settings/hours/{id}/delete", theirRule.getId()).param("doctorId", "1")).hasStatus(404);
        assertThat(post(staff, "/settings/days/{id}/delete", theirDay).param("doctorId", "1")).hasStatus(404);
        assertThat(doctors.findByIdAndClinicId(theirs.getId(), other.getId()).orElseThrow().getDisplayName())
                .isEqualTo("Dr. Elsewhere");
    }
}
