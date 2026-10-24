package app.clinicreminders.booking;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.clinicreminders.common.AppProperties;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
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
 * {@code day:2026-11-03}, {@code slot:<epoch second>}, {@code slots:more}, {@code confirm:patient:7},
 * {@code mine:cancel:45}...
 */
@Component
class BookingMessages {

    /** Meta's limit on a list row title. */
    private static final int ROW_TITLE = 24;
    /** Meta's limit on a list row description. */
    private static final int ROW_DESCRIPTION = 72;
    /** The confirmation's other rows (someone else, another time, don't book) leave room for this many patients. */
    static final int MAX_PATIENT_ROWS = 7;

    /** An appointment to come under "My appointment"; {@code patient} is named when a family shares the number. */
    record Upcoming(long appointmentId, @Nullable String patient, String doctor, Instant startsAt) {
    }

    private final MessageSource messages;
    private final AppProperties app;

    BookingMessages(MessageSource messages, AppProperties app) {
        this.messages = messages;
        this.app = app;
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

    /**
     * Who the appointment is for: the patients on this number (a family shares one), or for a new
     * number its WhatsApp profile name; then Someone else, Another time and Don't book.
     *
     * @param household   the number's patients, at most {@link #MAX_PATIENT_ROWS}
     * @param profileName the sender's WhatsApp name, offered when the number has no patient yet
     */
    ListMessage confirm(String to, Language language, String doctor, Instant startsAt, ZoneId zone,
                        List<Patient> household, @Nullable String profileName) {
        List<ListRow> rows = new ArrayList<>();
        for (Patient patient : household) {
            rows.add(new ListRow("confirm:patient:" + patient.getId(), fit(patient.getName()), null));
        }
        if (household.isEmpty()) {
            boolean named = profileName != null && !profileName.isBlank();
            rows.add(new ListRow("confirm:me", named ? fit(profileName.strip()) : text("booking.confirm.me", language),
                    named ? text("booking.confirm.me", language) : null));
        }
        rows.add(new ListRow("confirm:new", text("booking.confirm.someone-else", language),
                text("booking.confirm.someone-else.hint", language)));
        rows.add(new ListRow("confirm:other", text("booking.confirm.other", language), null));
        rows.add(new ListRow("confirm:cancel", text("booking.confirm.cancel", language), null));
        return new ListMessage(to, text("booking.confirm.body", language, doctor, date(startsAt.atZone(zone), language),
                time(startsAt, zone, language)), text("booking.confirm.button", language),
                text("booking.confirm.section", language), rows);
    }

    /** After Someone else: the patient's name, typed. */
    TextMessage askName(String to, Language language) {
        return new TextMessage(to, text("booking.name.ask", language));
    }

    /** With the link to the appointment's "Add to my calendar" page. */
    TextMessage booked(String to, Language language, String patient, String doctor, Instant startsAt, ZoneId zone,
                       UUID calendarToken) {
        return new TextMessage(to, text("booking.booked", language, patient, doctor, date(startsAt.atZone(zone), language),
                time(startsAt, zone, language)) + "\n" + text("booking.booked.calendar", language, calendarLink(calendarToken)));
    }

    /** The public "Add to my calendar" page of an appointment. */
    String calendarLink(UUID calendarToken) {
        return app.link("/c/" + calendarToken);
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

    /** One appointment to come, with Cancel it / Keep it. */
    ReplyButtonsMessage myAppointment(String to, Language language, Upcoming appointment, ZoneId zone) {
        String date = date(appointment.startsAt().atZone(zone), language);
        String time = time(appointment.startsAt(), zone, language);
        String body = appointment.patient() == null
                ? text("booking.mine.body", language, appointment.doctor(), date, time)
                : text("booking.mine.body-for", language, appointment.patient(), appointment.doctor(), date, time);
        return new ReplyButtonsMessage(to, body,
                List.of(button("mine:cancel:" + appointment.appointmentId(), "booking.mine.cancel", language),
                        button("mine:keep", "booking.mine.keep", language)));
    }

    /** Several appointments to come (at most 10): picking one shows it with Cancel it / Keep it. */
    ListMessage myAppointments(String to, Language language, List<Upcoming> appointments, ZoneId zone) {
        List<ListRow> rows = appointments.stream()
                .map(a -> {
                    String details = time(a.startsAt(), zone, language) + " · " + a.doctor()
                            + (a.patient() == null ? "" : " · " + a.patient());
                    return new ListRow("mine:show:" + a.appointmentId(), fit(date(a.startsAt().atZone(zone), language)),
                            details.length() <= ROW_DESCRIPTION ? details : details.substring(0, ROW_DESCRIPTION - 1).strip() + "…");
                })
                .toList();
        return new ListMessage(to, text("booking.mine.list", language), text("booking.mine.button", language),
                text("booking.mine.section", language), rows);
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

    /** Doctor and patient names and long dates are cut to fit a list row. */
    static String fit(String title) {
        return title.length() <= ROW_TITLE ? title : title.substring(0, ROW_TITLE - 1).strip() + "…";
    }
}
