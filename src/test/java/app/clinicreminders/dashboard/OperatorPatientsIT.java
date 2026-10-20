package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.staff.StaffPrincipal;
import app.clinicreminders.staff.StaffUser;
import app.clinicreminders.staff.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The operator's Patients page, across clinics. Now is Wednesday 14 October 2026, 12:00 in Beirut.
 * Rana (clinic A) was seen last week and is booked next week; Karim (A) has never had an appointment
 * and opted out; Nour (B) was last seen in March.
 */
class OperatorPatientsIT extends DashboardTest {

    private static final Duration HALF_HOUR = Duration.ofMinutes(30);

    @Autowired
    StaffUserRepository users;

    @Autowired
    PatientRepository patients;

    private StaffPrincipal operator;
    private Clinic clinicA;
    private Clinic clinicB;

    @BeforeEach
    void patientsInTwoClinics() {
        operator = StaffPrincipal.of(users.save(StaffUser.operator("op.patients", "{noop}x", Language.EN)));
        clinicA = data.clinic("Clinic Alpha");
        clinicB = data.clinic("Clinic Beta");
        Doctor doctorA = data.doctor(clinicA, "Dr. Khoury", 30);
        Doctor doctorB = data.doctor(clinicB, "Dr. Saab", 30);
        Patient rana = data.patient(clinicA, "Rana Haddad", "+96170123456", Language.AR);
        data.appointment(doctorA, rana, Instant.parse("2026-10-07T06:00:00Z"), HALF_HOUR);
        data.appointment(doctorA, rana, Instant.parse("2026-10-21T07:30:00Z"), HALF_HOUR);
        Patient karim = data.patient(clinicA, "Karim Saleh", "+96171222333", Language.EN);
        karim.optOut();
        patients.save(karim);
        Patient nour = data.patient(clinicB, "Nour Khalil", "+96103456789", Language.AR);
        data.appointment(doctorB, nour, Instant.parse("2026-03-02T07:00:00Z"), HALF_HOUR);
    }

    @Test
    void listsEveryClinicsPatientsWithTheirAppointments() {
        assertThat(get(operator, "/operator/patients")).hasStatusOk().bodyText()
                .contains("Patients found: 3", "Rana Haddad", "Karim Saleh", "Nour Khalil", "Clinic Alpha", "Clinic Beta")
                .as("Rana's next appointment, in her clinic's time").contains("Wed 21 Oct 10:30 AM")
                .contains("href=\"/operator/clinics/" + clinicB.getId() + "\"");
    }

    @Test
    void filtersByClinicSearchAndLanguage() throws Exception {
        assertThat(names("clinic=" + clinicA.getId())).contains("Rana Haddad", "Karim Saleh").doesNotContain("Nour Khalil");
        assertThat(names("q=haddad")).contains("Rana Haddad").doesNotContain("Karim Saleh", "Nour Khalil");
        assertThat(names("q=71 222")).as("any part of the phone").contains("Karim Saleh").doesNotContain("Rana Haddad");
        assertThat(names("q=03 456")).as("a local number").contains("Nour Khalil").doesNotContain("Karim Saleh");
        assertThat(names("language=en")).contains("Karim Saleh").doesNotContain("Rana Haddad", "Nour Khalil");
    }

    @Test
    void filtersByBookingLastAppointmentAndMessages() throws Exception {
        assertThat(names("booking=upcoming")).contains("Rana Haddad").doesNotContain("Karim Saleh", "Nour Khalil");
        assertThat(names("booking=none")).contains("Karim Saleh", "Nour Khalil").doesNotContain("Rana Haddad");
        assertThat(names("last=last-30-days")).contains("Rana Haddad").doesNotContain("Karim Saleh", "Nour Khalil");
        assertThat(names("last=over-6-months")).contains("Nour Khalil").doesNotContain("Rana Haddad", "Karim Saleh");
        assertThat(names("last=never")).contains("Karim Saleh").doesNotContain("Rana Haddad", "Nour Khalil");
        assertThat(names("messages=opted-out")).contains("Karim Saleh").doesNotContain("Rana Haddad", "Nour Khalil");
        assertThat(names("messages=reachable")).contains("Rana Haddad", "Nour Khalil").doesNotContain("Karim Saleh");
        assertThat(names("clinic=" + clinicA.getId() + "&booking=none&messages=opted-out")).contains("Patients found: 1", "Karim Saleh");
    }

    @Test
    void sortsByNameNextOrLastAppointment() throws Exception {
        String byName = names("");
        assertThat(byName.indexOf("Karim Saleh")).isLessThan(byName.indexOf("Nour Khalil"));
        assertThat(byName.indexOf("Nour Khalil")).isLessThan(byName.indexOf("Rana Haddad"));
        String byLast = names("sort=last-appointment");
        assertThat(byLast.indexOf("Rana Haddad")).as("seen last week").isLessThan(byLast.indexOf("Nour Khalil"));
        assertThat(byLast.indexOf("Nour Khalil")).as("never seen last").isLessThan(byLast.indexOf("Karim Saleh"));
    }

    @Test
    void activeFiltersShowAsChipsThatRemoveThemselves() {
        assertThat(get(operator, "/operator/patients?language=en&booking=none")).bodyText()
                .contains("class=\"filter-chip\" href=\"/operator/patients?booking=none\"", "English")
                .contains("href=\"/operator/patients?language=en\"", "No upcoming appointment", "Clear all");
        assertThat(get(operator, "/operator/patients")).bodyText().doesNotContain("class=\"filter-chip\"");
    }

    @Test
    void loadsTwentyFiveAtATimeAsYouScroll() {
        for (int i = 0; i < 26; i++) {
            data.patient(clinicB, "Zed " + (100 + i), "+9617099" + (1000 + i), Language.AR);
        }

        assertThat(get(operator, "/operator/patients?sort=name")).bodyText()
                .contains("Patients found: 29", "class=\"load-more\"", "hx-get=\"/operator/patients?sort=name&amp;page=2\"")
                .doesNotContain("Zed 125");
        assertThat(get(operator, "/operator/patients?sort=name&page=2").header("HX-Request", "true")).bodyText()
                .as("only the next rows, to append").contains("Zed 125").doesNotContain("<html", "class=\"load-more\"");
        assertThat(get(operator, "/operator/patients?page=2")).bodyText().as("without JavaScript, a page of its own")
                .contains("<html", "Zed 125");
        assertThat(get(operator, "/operator/patients?page=99")).hasStatusOk().bodyText().contains("Zed 125");
    }

    @Test
    void oddValuesFallBackToNoFilter() throws Exception {
        assertThat(names("booking=bogus&last=x&messages=&sort=up&language=fr&clinic=999999"))
                .contains("Patients found: 3", "Rana Haddad", "Karim Saleh", "Nour Khalil");
    }

    @Test
    void onlyOperatorsSeeIt() {
        assertThat(get(secretaryOf(clinicA), "/operator/patients")).hasStatus(403);
        assertThat(get(operator, "/")).hasRedirectedUrl("/operator");
        assertThat(get(operator, "/operator")).bodyText().contains("href=\"/operator/patients\"");
    }

    /** The results part of the page (the filter form lists clinics, so it's left out). */
    private String names(String query) throws Exception {
        return get(operator, "/operator/patients?" + query).exchange().getResponse().getContentAsString()
                .replaceAll("(?s)<form.*?</form>", "");
    }
}
