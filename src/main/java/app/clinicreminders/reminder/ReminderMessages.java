package app.clinicreminders.reminder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.whatsapp.OutboundMessage.TemplateMessage;
import app.clinicreminders.whatsapp.OutboundMessage.TextMessage;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Builds what the patient receives, in their language. Template names and parameters follow
 * SPEC section 6; date and time patterns and the acknowledgment text are in the message bundles.
 */
@Component
public class ReminderMessages {

    /** "Hello {{1}}, ... your appointment with {{2}} tomorrow, {{3}} at {{4}}." */
    public static final String H24_TEMPLATE = "appointment_reminder";
    /** "Hello {{1}}, your appointment with {{2}} is today at {{3}}." */
    public static final String H2_TEMPLATE = "appointment_reminder_soon";
    /**
     * "Hello {{1}}, we're sorry: your appointment with {{2}} on {{3}} at {{4}} is cancelled because the
     * doctor is not available then." One quick reply, Book another time, which starts a booking.
     */
    public static final String DOCTOR_AWAY_TEMPLATE = "appointment_cancelled_by_clinic";

    private final MessageSource messages;
    private final ReminderProperties properties;

    ReminderMessages(MessageSource messages, ReminderProperties properties) {
        this.messages = messages;
        this.properties = properties;
    }

    public TemplateMessage reminder(ReminderKind kind, Patient patient, Doctor doctor, Instant startsAt, ZoneId zone) {
        Language language = patient.getLanguage();
        ZonedDateTime local = startsAt.atZone(zone);
        String code = properties.templateLanguageCode(language);
        return switch (kind) {
            case H24 -> new TemplateMessage(patient.getPhoneE164(), H24_TEMPLATE, code,
                    List.of(patient.getName(), doctor.getDisplayName(), date(local, language), time(local, language)));
            case H2 -> new TemplateMessage(patient.getPhoneE164(), H2_TEMPLATE, code,
                    List.of(patient.getName(), doctor.getDisplayName(), time(local, language)));
        };
    }

    /** Sent when the clinic cancels because the doctor won't be there; the patient may not have written lately. */
    public TemplateMessage doctorAway(Patient patient, Doctor doctor, Instant startsAt, ZoneId zone) {
        Language language = patient.getLanguage();
        ZonedDateTime local = startsAt.atZone(zone);
        return new TemplateMessage(patient.getPhoneE164(), DOCTOR_AWAY_TEMPLATE, properties.templateLanguageCode(language),
                List.of(patient.getName(), doctor.getDisplayName(), date(local, language), time(local, language)));
    }

    /** Sent after the patient taps Cancel. */
    public TextMessage cancellationAcknowledgment(Patient patient, Doctor doctor, Instant startsAt, ZoneId zone) {
        Language language = patient.getLanguage();
        ZonedDateTime local = startsAt.atZone(zone);
        String text = messages.getMessage("reminder.cancelled",
                new Object[] {doctor.getDisplayName(), date(local, language), time(local, language)}, language.locale());
        return new TextMessage(patient.getPhoneE164(), text);
    }

    private String date(ZonedDateTime time, Language language) {
        return format(time, "patient.date-format", language.locale());
    }

    private String time(ZonedDateTime time, Language language) {
        return format(time, "patient.time-format", language.locale());
    }

    private String format(ZonedDateTime time, String patternKey, Locale locale) {
        return DateTimeFormatter.ofPattern(messages.getMessage(patternKey, null, locale), locale).format(time);
    }
}
