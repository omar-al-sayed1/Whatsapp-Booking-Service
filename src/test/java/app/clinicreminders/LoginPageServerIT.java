package app.clinicreminders;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/**
 * The sign-in page through a real server, with no session, as right after logging out. MockMvc can't
 * catch this one: it never commits a response, and a committed response is what stopped the page
 * at the form (no session could be created for the CSRF token any more).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "whatsapp.client=fake",
        "app.base-url=https://reminders.test",
        "whatsapp.app-secret=test-app-secret",
        "whatsapp.verify-token=test-verify-token",
        "whatsapp.token-encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "whatsapp.inbox.async=false",
        "reminders.scheduler.enabled=false"})
@Import(IntegrationTestConfiguration.class)
class LoginPageServerIT {

    @LocalServerPort
    int port;

    @Test
    void theSignInFormIsThereWithoutASession() {
        for (String page : new String[] {"/login", "/login?logout", "/login?error"}) {
            String html = RestClient.create("http://localhost:" + port).get().uri(page).retrieve().body(String.class);

            assertThat(html).as(page).contains("name=\"username\"", "name=\"password\"", "name=\"_csrf\"", "</html>");
        }
    }
}
