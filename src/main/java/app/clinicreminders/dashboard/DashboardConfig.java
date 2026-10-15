package app.clinicreminders.dashboard;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;

@Configuration(proxyBeanMethods = false)
class DashboardConfig {

    /** The bean name is what Spring MVC looks for. */
    @Bean
    LocaleResolver localeResolver() {
        return new StaffLocaleResolver();
    }
}
