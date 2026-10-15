package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.staff.StaffPrincipal;
import app.clinicreminders.staff.StaffRole;
import app.clinicreminders.staff.StaffUser;
import app.clinicreminders.staff.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

class SecurityIT extends DashboardTest {

    @Autowired
    StaffUserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    private Clinic clinic;

    @BeforeEach
    void clinicWithASecretary() {
        clinic = data.clinic("A");
        users.save(StaffUser.clinicStaff(clinic.getId(), "rana.sec", passwordEncoder.encode("s3cret-pass"),
                StaffRole.SECRETARY, Language.EN));
    }

    @Test
    void visitorsAreSentToTheLoginPage() {
        assertThat(mvc.get().uri("/week")).hasStatus3xxRedirection().headers().hasValue("Location", "/login");
    }

    @Test
    void htmxRequestsWithoutASessionGetARedirectHtmxCanFollow() {
        assertThat(mvc.get().uri("/today/list?date=2026-10-14").header("HX-Request", "true"))
                .hasStatus(401)
                .headers().hasValue("HX-Redirect", "/login");
    }

    @Test
    void loggingInIgnoresUsernameCaseAndOpensToday() {
        assertThat(mvc.post().uri("/login").param("username", "Rana.Sec").param("password", "s3cret-pass").with(csrf()))
                .hasStatus3xxRedirection()
                .headers().hasValue("Location", "/");
    }

    @Test
    void aWrongPasswordOrADisabledUserIsRefused() {
        assertThat(mvc.post().uri("/login").param("username", "rana.sec").param("password", "guess").with(csrf()))
                .headers().hasValue("Location", "/login?error");

        jdbc.update("update staff_user set enabled = false");
        assertThat(mvc.post().uri("/login").param("username", "rana.sec").param("password", "s3cret-pass").with(csrf()))
                .headers().hasValue("Location", "/login?error");
    }

    @Test
    void operatorsHaveTheirOwnPagesAndNoClinic() {
        StaffPrincipal operator = StaffPrincipal.of(users.save(StaffUser.operator("omar", "{noop}x", Language.EN)));

        assertThat(mvc.get().uri("/").with(user(operator))).hasStatus3xxRedirection()
                .headers().hasValue("Location", "/operator");
        assertThat(mvc.get().uri("/operator").with(user(operator))).hasStatusOk();
        assertThat(mvc.get().uri("/settings").with(user(operator))).hasStatus(403);
    }

    @Test
    void clinicStaffCannotOpenOperatorPages() {
        assertThat(get(secretaryOf(clinic), "/operator")).hasStatus(403);
    }

    @Test
    void changesNeedACsrfToken() {
        assertThat(mvc.post().uri("/settings/clinic").with(user(secretaryOf(clinic)))
                .param("defaultLanguage", "en"))
                .hasStatus(403);
    }

    @Test
    void publicEndpointsStayOpen() {
        assertThat(mvc.get().uri("/login")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
        assertThat(mvc.get().uri("/css/app.css")).hasStatusOk();
        assertThat(mvc.get().uri("/webjars/htmx.org/2.0.11/dist/htmx.min.js")).hasStatusOk();
        assertThat(mvc.get().uri("/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=test-verify-token&hub.challenge=7"))
                .hasStatusOk().hasBodyTextEqualTo("7");
    }
}
