package app.clinicreminders;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The context only starts if Flyway applied every migration and Hibernate's schema validation
 * matched every entity against it ({@code ddl-auto: validate}).
 */
class ApplicationIT extends IntegrationTest {

    @Autowired
    MockMvcTester mvc;

    @Test
    void healthIsUpIncludingTheDatabase() {
        assertThat(mvc.get().uri("/actuator/health"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("UP");
    }

    @Test
    void onlyTheHealthEndpointIsExposed() {
        assertThat(mvc.get().uri("/actuator/env")).hasStatus(404);
        assertThat(mvc.get().uri("/actuator/beans")).hasStatus(404);
    }

    @Test
    void flywayAppliedTheBaseline() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success", Integer.class);
        assertThat(applied).isEqualTo(1);
    }
}
