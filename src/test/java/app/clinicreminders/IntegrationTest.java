package app.clinicreminders;

import java.time.Instant;

import app.clinicreminders.whatsapp.FakeWhatsAppClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Base class for Testcontainers tests. Every subclass shares one Spring context and one Postgres
 * container. Tests commit for real (no rollback), so the database is truncated before each test;
 * that keeps concurrency tests (M3, M5) honest. WhatsApp uses the fake client, and webhook
 * deliveries are processed only when a test calls {@code WebhookInboxProcessor.drain()}, reminders
 * only when it calls {@code ReminderSender.sendDue()}.
 */
@SpringBootTest(properties = {
        "whatsapp.client=fake",
        "app.base-url=https://reminders.test",
        "whatsapp.app-secret=test-app-secret",
        "whatsapp.verify-token=test-verify-token",
        "whatsapp.token-encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "whatsapp.inbox.async=false",
        "reminders.scheduler.enabled=false"})
@AutoConfigureMockMvc
@Import(IntegrationTestConfiguration.class)
public abstract class IntegrationTest {

    protected static final Instant DEFAULT_NOW = Instant.parse("2026-10-14T09:00:00Z");

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected TestData data;

    @Autowired
    protected FakeWhatsAppClient whatsApp;

    @Autowired
    protected RecordingSleeper sleeper;

    @BeforeEach
    void resetState() {
        jdbc.execute("truncate table clinic, message_rate, webhook_inbox restart identity cascade");
        clock.setInstant(DEFAULT_NOW);
        whatsApp.reset();
        sleeper.reset();
    }
}
