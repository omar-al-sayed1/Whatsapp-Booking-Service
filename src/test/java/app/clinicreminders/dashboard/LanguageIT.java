package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.staff.StaffPrincipal;
import app.clinicreminders.staff.StaffRole;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class LanguageIT extends DashboardTest {

    @Test
    void switchingLanguageReturnsToThePageAndIsSavedForNextTime() {
        Clinic clinic = data.clinic("A");
        StaffPrincipal staff = staffOf(clinic, StaffRole.OWNER, Language.EN);

        assertThat(post(staff, "/language").param("lang", "ar").header("Referer", "http://localhost/week?start=2026-10-19"))
                .hasStatus3xxRedirection()
                .headers().hasValue("Location", "/week?start=2026-10-19");

        assertThat(jdbc.queryForObject("select language from staff_user where id = ?", String.class, staff.id()))
                .isEqualTo("ar");
    }

    @Test
    void theLoginPageSwitchesLanguageAndDirectionTogether() {
        var switched = mvc.get().uri("/login?lang=ar").header("Accept-Language", "en").exchange();
        assertThat(switched).hasStatus3xxRedirection().headers().hasValue("Location", "/login");

        assertThat(mvc.get().uri("/login").session((MockHttpSession) switched.getRequest().getSession())
                .header("Accept-Language", "en"))
                .bodyText().contains("dir=\"rtl\"", "تسجيل الدخول", "English");
    }

    @Test
    void theRedirectNeverLeavesTheSite() {
        assertThat(AccountController.samePagePath("https://evil.example/steal?x=1")).isEqualTo("/steal?x=1");
        assertThat(AccountController.samePagePath("//evil.example/steal")).isEqualTo("/steal");
        assertThat(AccountController.samePagePath("javascript:alert(1)")).isEqualTo("/");
        assertThat(AccountController.samePagePath(null)).isEqualTo("/");
        assertThat(AccountController.samePagePath("http://localhost/patients?q=rana")).isEqualTo("/patients?q=rana");
    }
}
