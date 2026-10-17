package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.Language;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.staff.StaffPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SettingsPageIT extends DashboardTest {

    @Autowired
    ClinicRepository clinics;

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
    void settingsHoldsTheClinicOnly() {
        assertThat(get(staff, "/settings")).hasStatusOk().bodyText()
                .contains("<title>Settings</title>", "name=\"h24Enabled\"")
                .doesNotContain("class=\"tabs\"", "Dr. Khoury", "Add a doctor");
    }

    /** Doctors used to be a tab here; old links land on the Doctors page. */
    @Test
    void theOldDoctorsTabLeadsToDoctors() {
        assertThat(get(staff, "/settings?tab=doctors")).hasRedirectedUrl("/doctors");
    }

    /** The sidebar lights up Doctors on the doctors pages and Settings on settings. */
    @Test
    void theSidebarShowsDoctorsApartFromSettings() {
        String doctorsCurrent = "href=\"/doctors\" aria-current=\"page\" class=\"active\"";
        String settingsCurrent = "href=\"/settings\" aria-current=\"page\" class=\"active\"";

        assertThat(get(staff, "/doctors")).bodyText().contains(doctorsCurrent).doesNotContain(settingsCurrent);
        assertThat(get(staff, "/doctors/{id}", doctor.getId())).bodyText()
                .contains(doctorsCurrent).doesNotContain(settingsCurrent);
        assertThat(get(staff, "/settings")).bodyText().contains(settingsCurrent, "href=\"/doctors\"")
                .doesNotContain(doctorsCurrent);
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
}
