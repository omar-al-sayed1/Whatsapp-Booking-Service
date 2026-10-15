package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.staff.StaffPrincipal;
import app.clinicreminders.staff.StaffRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Every page renders in both languages, right-to-left in Arabic. */
class DashboardPagesIT extends DashboardTest {

    private Clinic clinic;
    private Doctor doctor;

    @BeforeEach
    void clinicWithAnAppointment() {
        clinic = data.connectedClinic("Clinic A", "pn-a");
        doctor = data.doctor(clinic, "Dr. Khoury", 30);
        data.weekly(doctor, DayOfWeek.WEDNESDAY, "09:00", "12:00");
        // Wednesday 14 October 2026 is "today" in tests (12:00 in Beirut).
        data.appointment(doctor, data.patient(clinic, "Rana", "+96170123456", Language.AR),
                Instant.parse("2026-10-14T10:00:00Z"), Duration.ofMinutes(30));
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    void everyPageRendersInTheUsersLanguageAndDirection(Language language) {
        StaffPrincipal staff = staffOf(clinic, StaffRole.SECRETARY, language);
        String dir = language == Language.AR ? "dir=\"rtl\"" : "dir=\"ltr\"";

        for (String page : new String[] {"/", "/week", "/patients", "/attention", "/settings",
                "/settings/doctors/" + doctor.getId(), "/appointments/new"}) {
            assertThat(get(staff, page)).as(page)
                    .hasStatusOk()
                    .bodyText().contains(dir, "Clinic A");
        }
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    void theLoginPageRendersWithoutAUser(Language language) {
        assertThat(mvc.get().uri("/login").header("Accept-Language", language.code()))
                .hasStatusOk()
                .bodyText().contains(language == Language.AR ? "تسجيل الدخول" : "Sign in");
    }
}
