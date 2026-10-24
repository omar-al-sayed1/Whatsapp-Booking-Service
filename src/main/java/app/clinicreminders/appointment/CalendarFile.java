package app.clinicreminders.appointment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * One appointment as an iCalendar file (RFC 5545), for "Add to my calendar": phone calendars,
 * Outlook and Google read it. Times are in UTC, so the patient's calendar shows them in its own zone.
 * It names the clinic and the doctor, never the patient. A cancelled appointment says so, and an
 * upcoming one rings an hour before.
 *
 * @param token the appointment's calendar token: the event's id, so adding it twice updates one event
 */
public record CalendarFile(UUID token, Instant start, Instant end, String summary, @Nullable String location,
                           String description, String alarm, boolean cancelled) {

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    /** RFC 5545 section 3.1: lines longer than this many octets are folded. */
    private static final int LINE_OCTETS = 75;

    public String render(Instant now) {
        List<String> lines = new ArrayList<>(List.of(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//Clinic Reminders//Appointment//EN",
                "CALSCALE:GREGORIAN",
                "METHOD:PUBLISH",
                "BEGIN:VEVENT",
                "UID:" + token + "@clinic-reminders",
                "DTSTAMP:" + UTC.format(now),
                "DTSTART:" + UTC.format(start),
                "DTEND:" + UTC.format(end),
                "SUMMARY:" + text(summary)));
        if (location != null && !location.isBlank()) {
            lines.add("LOCATION:" + text(location.strip()));
        }
        lines.add("DESCRIPTION:" + text(description));
        lines.add("STATUS:" + (cancelled ? "CANCELLED" : "CONFIRMED"));
        if (!cancelled) {
            lines.addAll(List.of("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:" + text(alarm), "TRIGGER:-PT1H",
                    "END:VALARM"));
        }
        lines.addAll(List.of("END:VEVENT", "END:VCALENDAR"));
        StringBuilder file = new StringBuilder();
        for (String line : lines) {
            file.append(fold(line)).append("\r\n");
        }
        return file.toString();
    }

    /** A TEXT value: backslashes, semicolons, commas and line breaks escaped (RFC 5545 section 3.3.11). */
    static String text(String value) {
        return value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
    }

    /** Splits a line over 75 octets into continuation lines starting with a space, never inside a character. */
    static String fold(String line) {
        StringBuilder folded = new StringBuilder();
        int octets = 0;
        int i = 0;
        while (i < line.length()) {
            int codePoint = line.codePointAt(i);
            String character = new String(Character.toChars(codePoint));
            int size = character.getBytes(StandardCharsets.UTF_8).length;
            if (octets + size > LINE_OCTETS) {
                folded.append("\r\n ");
                octets = 1; // the leading space
            }
            folded.append(character);
            octets += size;
            i += Character.charCount(codePoint);
        }
        return folded.toString();
    }
}
