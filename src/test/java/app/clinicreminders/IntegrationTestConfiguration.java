package app.clinicreminders;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Shared by every {@link IntegrationTest}. Top-level on purpose: Spring Framework 7.1 starts
 * treating static nested configuration classes as default test configuration.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({TestcontainersConfiguration.class, TestData.class})
class IntegrationTestConfiguration {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(IntegrationTest.DEFAULT_NOW);
    }
}
