package app.clinicreminders.dashboard;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

import app.clinicreminders.common.Language;
import org.springframework.context.MessageSource;

/** Dates and times in the clinic's zone and the user's language; patterns come from the message bundles. */
final class Formats {

    private final ZoneId zone;
    private final Locale locale;
    private final DateTimeFormatter date;
    private final DateTimeFormatter dayShort;
    private final DateTimeFormatter time;

    Formats(ZoneId zone, Language language, MessageSource messages) {
        this.zone = zone;
        this.locale = language.locale();
        this.date = DateTimeFormatter.ofPattern(messages.getMessage("format.date", null, locale), locale);
        this.dayShort = DateTimeFormatter.ofPattern(messages.getMessage("format.day-short", null, locale), locale);
        this.time = DateTimeFormatter.ofPattern(messages.getMessage("format.time", null, locale), locale);
    }

    String date(LocalDate value) {
        return date.format(value);
    }

    String dayShort(LocalDate value) {
        return dayShort.format(value);
    }

    String time(LocalTime value) {
        return time.format(value);
    }

    String time(Instant value) {
        return time.format(value.atZone(zone));
    }

    String dateTime(Instant value) {
        return dayShort.format(value.atZone(zone)) + " " + time(value);
    }

    String day(DayOfWeek day) {
        return day.getDisplayName(TextStyle.FULL, locale);
    }

    /** "Mon"; Arabic has no abbreviations, so the full name there. */
    String weekdayShort(LocalDate value) {
        return value.getDayOfWeek().getDisplayName(TextStyle.SHORT, locale);
    }
}
