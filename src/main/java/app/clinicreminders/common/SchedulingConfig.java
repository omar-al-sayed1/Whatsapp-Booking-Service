package app.clinicreminders.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables {@code @Scheduled}; each scheduled component has its own on/off property. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {
}
