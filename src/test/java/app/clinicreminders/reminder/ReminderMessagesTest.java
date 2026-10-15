package app.clinicreminders.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.whatsapp.OutboundMessage.TemplateMessage;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

class ReminderMessagesTest {

    private static final ZoneId BEIRUT = ZoneId.of("Asia/Beirut");
    /** Tuesday 3 November 2026, 10:30 in Beirut. */
    private static final Instant START = Instant.parse("2026-11-03T08:30:00Z");

    private final ReminderMessages messages = new ReminderMessages(messageSource(),
            new ReminderProperties(null, Map.of("ar", "ar", "en", "en_US"), List.of(), List.of()));

    private final Patient rana = new Patient(1, "Rana", "+96170123456", Language.AR);
    private final Patient john = new Patient(1, "John", "+96170654321", Language.EN);
    private final Doctor arabicName = new Doctor(1, "د. خوري", 30);
    private final Doctor englishName = new Doctor(1, "Dr. Khoury", 30);

    @Test
    void h24InArabicUsesLevantineMonthNames() {
        TemplateMessage message = messages.reminder(ReminderKind.H24, rana, arabicName, START, BEIRUT);

        assertThat(message.to()).isEqualTo("+96170123456");
        assertThat(message.name()).isEqualTo("appointment_reminder");
        assertThat(message.languageCode()).isEqualTo("ar");
        assertThat(message.bodyParameters()).containsExactly("Rana", "د. خوري", "الثلاثاء 3 تشرين الثاني", "10:30 ص");
    }

    @Test
    void h2InEnglishUsesTheApprovedLanguageCode() {
        TemplateMessage message = messages.reminder(ReminderKind.H2, john, englishName, START, BEIRUT);

        assertThat(message.name()).isEqualTo("appointment_reminder_soon");
        assertThat(message.languageCode()).isEqualTo("en_US");
        assertThat(message.bodyParameters()).containsExactly("John", "Dr. Khoury", "10:30 AM");
    }

    @Test
    void cancellationAcknowledgmentInBothLanguages() {
        assertThat(messages.cancellationAcknowledgment(rana, arabicName, START, BEIRUT).body())
                .isEqualTo("تم إلغاء موعدك مع د. خوري يوم الثلاثاء 3 تشرين الثاني الساعة 10:30 ص. راسلنا في أي وقت لحجز موعد جديد.");
        assertThat(messages.cancellationAcknowledgment(john, englishName, START, BEIRUT).body())
                .isEqualTo("Your appointment with Dr. Khoury on Tuesday 3 November at 10:30 AM is cancelled. "
                        + "Message us any time to book again.");
    }

    @Test
    void timesAreInTheClinicsZoneEvenAcrossTheAutumnChange() {
        // Monday 26 October 2026 17:15 in Beirut is 15:15Z (UTC+2 after the change).
        TemplateMessage message = messages.reminder(ReminderKind.H2, john, englishName,
                Instant.parse("2026-10-26T15:15:00Z"), BEIRUT);

        assertThat(message.bodyParameters().get(2)).isEqualTo("5:15 PM");
    }

    static ResourceBundleMessageSource messageSource() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
