package app.clinicreminders;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Base class for Testcontainers tests. Every subclass shares one Spring context and one Postgres
 * container. Tests commit for real (no rollback), so the database is truncated before each test;
 * that keeps concurrency tests (M3, M5) honest.
 */
@SpringBootTest
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

    @BeforeEach
    void resetDatabaseAndClock() {
        jdbc.execute("truncate table clinic, message_rate restart identity cascade");
        clock.setInstant(DEFAULT_NOW);
    }
}
