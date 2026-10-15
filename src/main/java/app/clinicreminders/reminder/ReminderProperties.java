package app.clinicreminders.reminder;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import app.clinicreminders.common.Language;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code reminders.*}.
 *
 * @param templateLanguageCodes the language code each template was approved under, by patient language code
 * @param confirmLabels         the reminder templates' Confirm button labels, in every approved language
 * @param cancelLabels          the reminder templates' Cancel button labels, in every approved language
 */
@ConfigurationProperties("reminders")
public record ReminderProperties(
        Scheduler scheduler,
        Map<String, String> templateLanguageCodes,
        List<String> confirmLabels,
        List<String> cancelLabels) {

    /**
     * @param enabled off in tests, which run the sender explicitly
     * @param batchSize most reminders sent per poll, so one poll can't run for ever
     */
    public record Scheduler(boolean enabled, Duration pollInterval, int batchSize) {
    }

    public ReminderProperties {
        scheduler = scheduler == null ? new Scheduler(true, Duration.ofSeconds(30), 50) : scheduler;
        templateLanguageCodes = templateLanguageCodes == null ? Map.of() : Map.copyOf(templateLanguageCodes);
        confirmLabels = confirmLabels == null ? List.of() : List.copyOf(confirmLabels);
        cancelLabels = cancelLabels == null ? List.of() : List.copyOf(cancelLabels);
        for (Language language : Language.values()) {
            if (!templateLanguageCodes.containsKey(language.code())) {
                throw new IllegalStateException("reminders.template-language-codes has no entry for " + language.code());
            }
        }
    }

    public String templateLanguageCode(Language language) {
        return templateLanguageCodes.get(language.code());
    }
}
