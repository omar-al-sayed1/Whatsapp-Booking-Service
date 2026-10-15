package app.clinicreminders.booking;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.clinicreminders.common.Language;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.whatsapp.OutboundMessage.ListMessage;
import app.clinicreminders.whatsapp.OutboundMessage.ListRow;
import app.clinicreminders.whatsapp.OutboundMessage.ReplyButton;
import app.clinicreminders.whatsapp.OutboundMessage.ReplyButtonsMessage;
import app.clinicreminders.whatsapp.OutboundMessage.TextMessage;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Builds the booking flow's screens in the patient's language. Reply and row ids are what
 * {@link BookingFlow} reads back from the tap: {@code menu:book}, {@code doctor:12},
 * {@code day:2026-11-03}, {@code slot:<epoch second>}, {@code slots:more}, {@code confirm:yes},
 * {@code mine:cancel:45}...
 */
@Component
class BookingMessages {

    /** Meta's limit on a list row title. */
    private static final int ROW_TITLE = 24;

    private final MessageSource messages;

    BookingMessages(MessageSource messages) {
        this.messages = messages;
    }

    /** Book / My appointment / Info. */
    ReplyButtonsMessage menu(String to, Language language, boolean noUpcoming) {
        return new ReplyButtonsMessage(to, text(noUpcoming ? "booking.menu.no-upcoming" : "booking.menu.body", language),
                List.of(button("menu:book", "booking.menu.book", language),
                        button("menu:mine", "booking.menu.mine", language),
                        button("menu:info", "booking.menu.info", language)));
    }

    /** At most 10 doctors fit in a list; the caller passes at most 10. */
    ListMessage doctors(String to, Language language, List<Doctor> doctors) {
        List<ListRow> rows = doctors.stream()
                .map(doctor -> new ListRow("doctor:" + doctor.getId(), fit(doctor.getDisplayName()), null))
                .toList();
        return new ListMessage(to, text("booking.doctor.body", language), text("booking.doctor.button", language),
                text("booking.doctor.section", language), rows);
    }

    /**
     * @param freeTimes how many free times each day has, in date order
     * @param full      the day the patient picked has just filled up
     */
    ListMessage days(String to, Language language, Map<LocalDate, Integer> freeTimes, boolean full) {
        List<ListRow> rows = freeTimes.entrySet().stream()
                .map(day -> new ListRow("day:" + day.getKey(), fit(date(day.getKey(), language)),
                        // As a string: MessageFormat would pick the locale's digits, dates use Western ones.
                        text("booking.day.free", language, String.valueOf(day.getValue()))))
                .toList();
        return new ListMessage(to, text(full ? "booking.day.full" : "booking.day.body", language),
                text("booking.day.button", language), text("booking.day.section", language), rows);
    }

    /**
     * @param more  add a "Later times" row after these
     * @param taken the time the patient picked has just been taken
     */
    ListMessage times(String to, Language language, LocalDate day, List<Instant> times, boolean more, ZoneId zone,
                      boolean taken) {
        List<ListRow> rows = new ArrayList<>();
        for (Instant time : times) {
            rows.add(new ListRow("slot:" + time.getEpochSecond(), time(time, zone, language), null));
        }
        if (more) {
            rows.add(new ListRow("slots:more", text("booking.slot.later", language), null));
        }
        return new ListMessage(to, text(taken ? "booking.slot.taken" : "booking.slot.body", language, date(day, language)),
                text("booking.slot.button", language), text("booking.slot.section", language), rows);
    }

    /** Confirm / Another time / Cancel. */
    ReplyButtonsMessage confirm(String to, Language language, String doctor, Instant startsAt, ZoneId zone) {
        return new ReplyButtonsMessage(to, text("booking.confirm.body", language, doctor,
                date(startsAt.atZone(zone), language), time(startsAt, zone, language)),
                List.of(button("confirm:yes", "booking.confirm.yes", language),
                        button("confirm:other", "booking.confirm.other", language),
                        button("confirm:cancel", "booking.confirm.cancel", language)));
    }

    TextMessage booked(String to, Language language, String doctor, Instant startsAt, ZoneId zone) {
        return new TextMessage(to, text("booking.booked", language, doctor, date(startsAt.atZone(zone), language),
                time(startsAt, zone, language)));
    }

    TextMessage notBooked(String to, Language language) {
        return new TextMessage(to, text("booking.not-booked", language));
    }

    /** No doctor takes appointments: the secretary will call. */
    TextMessage noDoctors(String to, Language language) {
        return new TextMessage(to, text("booking.no-doctors", language));
    }

    /** Nothing free in the next 30 days: the secretary will call. */
    TextMessage noFreeDays(String to, Language language) {
        return new TextMessage(to, text("booking.no-free-days", language));
    }

    /** The next appointment, with Cancel it / Keep it. */
    ReplyButtonsMessage myAppointment(String to, Language language, String doctor, Instant startsAt, ZoneId zone,
                                      long appointmentId) {
        return new ReplyButtonsMessage(to, text("booking.mine.body", language, doctor,
                date(startsAt.atZone(zone), language), time(startsAt, zone, language)),
                List.of(button("mine:cancel:" + appointmentId, "booking.mine.cancel", language),
                        button("mine:keep", "booking.mine.keep", language)));
    }

    /** The clinic's address and opening hours from Settings, whichever are filled in. */
    TextMessage info(String to, Language language, @Nullable String address, @Nullable String hours) {
        List<String> lines = new ArrayList<>();
        if (address != null && !address.isBlank()) {
            lines.add(text("booking.info.address", language, address.strip()));
        }
        if (hours != null && !hours.isBlank()) {
            lines.add(text("booking.info.hours", language, hours.strip()));
        }
        return new TextMessage(to, lines.isEmpty() ? text("booking.info.none", language) : String.join("\n", lines));
    }

    private ReplyButton button(String id, String titleKey, Language language) {
        return new ReplyButton(id, text(titleKey, language));
    }

    private String text(String key, Language language, Object... args) {
        return messages.getMessage(key, args, language.locale());
    }

    private String date(TemporalAccessor date, Language language) {
        return format(date, "patient.date-format", language);
    }

    private String time(Instant time, ZoneId zone, Language language) {
        return format(time.atZone(zone), "patient.time-format", language);
    }

    private String format(TemporalAccessor value, String patternKey, Language language) {
        return DateTimeFormatter.ofPattern(text(patternKey, language), language.locale()).format(value);
    }

    /** Doctor names and long dates are cut to fit a list row. */
    static String fit(String title) {
        return title.length() <= ROW_TITLE ? title : title.substring(0, ROW_TITLE - 1).strip() + "…";
    }
}
