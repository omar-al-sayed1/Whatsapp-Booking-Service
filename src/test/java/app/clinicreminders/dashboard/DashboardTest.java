package app.clinicreminders.dashboard;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.util.concurrent.atomic.AtomicInteger;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.staff.StaffPrincipal;
import app.clinicreminders.staff.StaffRole;
import app.clinicreminders.staff.StaffUser;
import app.clinicreminders.staff.StaffUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;

/** Integration tests for dashboard pages: requests made as a logged-in staff user, with CSRF on posts. */
abstract class DashboardTest extends IntegrationTest {

    @Autowired
    protected MockMvcTester mvc;

    @Autowired
    private StaffUserRepository users;

    private final AtomicInteger sequence = new AtomicInteger();

    protected StaffPrincipal secretaryOf(Clinic clinic) {
        return staffOf(clinic, StaffRole.SECRETARY, Language.EN);
    }

    protected StaffPrincipal staffOf(Clinic clinic, StaffRole role, Language language) {
        StaffUser user = users.save(StaffUser.clinicStaff(clinic.getId(), "user" + sequence.incrementAndGet(),
                "{noop}secret", role, language));
        return StaffPrincipal.of(user);
    }

    protected MockMvcRequestBuilder get(StaffPrincipal staff, String uri, Object... variables) {
        return mvc.get().uri(uri, variables).with(user(staff));
    }

    protected MockMvcRequestBuilder post(StaffPrincipal staff, String uri, Object... variables) {
        return mvc.post().uri(uri, variables).with(user(staff)).with(csrf());
    }

    protected MockMvcRequestBuilder htmxPost(StaffPrincipal staff, String uri, Object... variables) {
        return post(staff, uri, variables).header("HX-Request", "true");
    }
}
