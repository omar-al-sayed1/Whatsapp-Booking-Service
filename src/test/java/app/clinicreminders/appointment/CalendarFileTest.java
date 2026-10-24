package app.clinicreminders.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CalendarFileTest {

    private static final UUID TOKEN = UUID.fromString("6f1c2a0e-1d2b-4c3d-9e8f-0a1b2c3d4e5f");
    /** Thursday 15 October 2026, 10:30 to 11:00 in Beirut. */
    private static final Instant START = Instant.parse("2026-10-15T07:30:00Z");
    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");

    @Test
    void anEventInUtcWithTheClinicAndDoctorAndAnAlarmAnHourBefore() {
        String file = file(false).render(NOW);

        assertThat(file).startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n").endsWith("END:VEVENT\r\nEND:VCALENDAR\r\n")
                .contains("UID:" + TOKEN + "@clinic-reminders\r\n", "DTSTAMP:20261014T090000Z\r\n",
                        "DTSTART:20261015T073000Z\r\n", "DTEND:20261015T080000Z\r\n",
                        "SUMMARY:Dr. Khoury · Dr. Demo\r\n", "LOCATION:Main street\\, Zahle\r\n",
                        "STATUS:CONFIRMED\r\n", "BEGIN:VALARM\r\n", "TRIGGER:-PT1H\r\n");
        assertThat(file.split("\r\n")).as("every line ends in CRLF, none is bare").noneMatch(line -> line.contains("\n"));
    }

    @Test
    void aCancelledAppointmentSaysSoAndRingsNoAlarm() {
        assertThat(file(true).render(NOW)).contains("STATUS:CANCELLED").doesNotContain("VALARM");
    }

    @Test
    void textIsEscaped() {
        assertThat(CalendarFile.text("a; b, c\\d\ne")).isEqualTo("a\\; b\\, c\\\\d\\ne");
    }

    @Test
    void longLinesAreFoldedAt75OctetsWithoutSplittingAnArabicLetter() {
        String line = "DESCRIPTION:" + "للإلغاء أو التغيير، راسل العيادة عبر واتساب. ".repeat(3);

        String folded = CalendarFile.fold(line);

        String[] parts = folded.split("\r\n");
        assertThat(parts).hasSizeGreaterThan(1);
        assertThat(Arrays.stream(parts).mapToInt(part -> part.getBytes(StandardCharsets.UTF_8).length)).allMatch(octets -> octets <= 75);
        assertThat(Arrays.stream(parts).skip(1)).allMatch(part -> part.startsWith(" "));
        assertThat(folded.replace("\r\n ", "")).isEqualTo(line);
    }

    private static CalendarFile file(boolean cancelled) {
        return new CalendarFile(TOKEN, START, START.plusSeconds(1800), "Dr. Khoury · Dr. Demo", "Main street, Zahle",
                "To cancel or change it, message the clinic on WhatsApp.", "Appointment with Dr. Khoury in one hour",
                cancelled);
    }
}
