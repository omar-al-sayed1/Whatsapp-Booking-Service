package app.clinicreminders.dashboard;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicSettings;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.PatientDirectory;
import app.clinicreminders.patient.PatientDirectory.Booking;
import app.clinicreminders.patient.PatientDirectory.Filter;
import app.clinicreminders.patient.PatientDirectory.LastAppointment;
import app.clinicreminders.patient.PatientDirectory.Messages;
import app.clinicreminders.patient.PatientDirectory.Sort;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The operator's Patients page: every clinic's patients, searched and filtered (clinic, language,
 * upcoming appointment, last appointment, opted out), sorted and paged. The filters live in the URL,
 * so a filtered list can be bookmarked or shared; results update as you type (htmx).
 */
@Controller
@RequestMapping("/operator/patients")
class OperatorPatientsController {

    /** A patient as listed; times in their clinic's zone. */
    record Row(long id, String name, String phone, long clinicId, String clinic, String language, boolean optedOut,
               @Nullable String next, @Nullable String last, long appointments, String added) {
    }

    /** An active filter, and the address of the list without it. */
    record Chip(String label, String removeUrl) {
    }

    private final PatientDirectory directory;
    private final ClinicSettings clinics;
    private final MessageSource messages;
    private final Clock clock;

    OperatorPatientsController(PatientDirectory directory, ClinicSettings clinics, MessageSource messages, Clock clock) {
        this.directory = directory;
        this.clinics = clinics;
        this.messages = messages;
        this.clock = clock;
    }

    @GetMapping
    String patients(HttpServletRequest request, Model model,
                    @RequestParam(defaultValue = "") String q,
                    @RequestParam(required = false) Long clinic,
                    @RequestParam(defaultValue = "") String language,
                    @RequestParam(defaultValue = "") String booking,
                    @RequestParam(defaultValue = "") String last,
                    @RequestParam(name = "messages", defaultValue = "") String reach,
                    @RequestParam(defaultValue = "") String sort,
                    @RequestParam(defaultValue = "1") int page) {
        Language viewer = StaffLocaleResolver.language(request);
        Locale locale = viewer.locale();
        List<Clinic> all = clinics.all();
        Map<Long, Clinic> byId = all.stream().collect(Collectors.toMap(Clinic::getId, Function.identity()));
        Filter filter = new Filter(q, clinic != null && byId.containsKey(clinic) ? clinic : null, language(language),
                parse(Booking.class, booking, Booking.ANY), parse(LastAppointment.class, last, LastAppointment.ANY),
                parse(Messages.class, reach, Messages.ANY), parse(Sort.class, sort, Sort.NAME), page);
        Instant now = clock.instant();
        PatientDirectory.Page found = directory.search(filter, now);

        Map<ZoneId, Formats> formats = new HashMap<>();
        List<Row> rows = found.rows().stream().map(r -> {
            Clinic owner = byId.get(r.clinicId());
            Formats f = formats.computeIfAbsent(owner.getTimezone(), zone -> new Formats(zone, viewer, messages));
            ZoneId zone = owner.getTimezone();
            return new Row(r.id(), r.name(), r.phoneE164(), r.clinicId(), owner.getName(), r.language().code(), r.optedOut(),
                    r.nextAppointment() == null ? null : f.dateTime(r.nextAppointment()),
                    r.lastAppointment() == null ? null : f.dayShort(r.lastAppointment().atZone(zone).toLocalDate()),
                    r.appointments(), f.dayShort(r.createdAt().atZone(zone).toLocalDate()));
        }).toList();

        model.addAttribute("rows", rows);
        model.addAttribute("total", found.total());
        model.addAttribute("page", found.page());
        model.addAttribute("pages", found.pages());
        model.addAttribute("previousUrl", found.page() > 1 ? withPage(request, found.page() - 1) : null);
        model.addAttribute("nextUrl", found.page() < found.pages() ? withPage(request, found.page() + 1) : null);
        model.addAttribute("clinics", all);
        model.addAttribute("filter", filter);
        model.addAttribute("q", q);
        model.addAttribute("chips", chips(request, filter, byId, locale));
        model.addAttribute("current", Map.of(
                "clinic", filter.clinicId() == null ? "" : filter.clinicId().toString(),
                "language", filter.language() == null ? "" : filter.language().code(),
                "booking", filter.booking() == Booking.ANY ? "" : value(filter.booking()),
                "last", filter.lastAppointment() == LastAppointment.ANY ? "" : value(filter.lastAppointment()),
                "messages", filter.messages() == Messages.ANY ? "" : value(filter.messages()),
                "sort", filter.sort() == Sort.NAME ? "" : value(filter.sort())));
        return "dashboard/operator-patients";
    }

    /** The active filters as chips; each links to the list without it (back on page 1). */
    private List<Chip> chips(HttpServletRequest request, Filter filter, Map<Long, Clinic> byId, Locale locale) {
        List<Chip> chips = new ArrayList<>();
        if (!filter.query().isBlank()) {
            chips.add(chip(request, "q", text("operator.patients.chip.search", locale) + ": " + filter.query().strip()));
        }
        if (filter.clinicId() != null) {
            chips.add(chip(request, "clinic", text("operator.patients.clinic", locale) + ": "
                    + byId.get(filter.clinicId()).getName()));
        }
        if (filter.language() != null) {
            chips.add(chip(request, "language", text("language." + filter.language().code(), locale)));
        }
        if (filter.booking() != Booking.ANY) {
            chips.add(chip(request, "booking", text("operator.patients.booking." + value(filter.booking()), locale)));
        }
        if (filter.lastAppointment() != LastAppointment.ANY) {
            chips.add(chip(request, "last", text("operator.patients.last", locale) + ": "
                    + text("operator.patients.last." + value(filter.lastAppointment()), locale)));
        }
        if (filter.messages() != Messages.ANY) {
            chips.add(chip(request, "messages", text("operator.patients.messages." + value(filter.messages()), locale)));
        }
        return chips;
    }

    private static Chip chip(HttpServletRequest request, String parameter, String label) {
        return new Chip(label, list(request).replaceQueryParam(parameter).replaceQueryParam("page").build().toUriString());
    }

    private static String withPage(HttpServletRequest request, int page) {
        return list(request).replaceQueryParam("page", page).build().toUriString();
    }

    private static UriComponentsBuilder list(HttpServletRequest request) {
        return UriComponentsBuilder.fromPath("/operator/patients").query(request.getQueryString());
    }

    private String text(String key, Locale locale) {
        return messages.getMessage(key, null, key, locale);
    }

    /** As in the URL and the message keys: "last-30-days" for LAST_30_DAYS. */
    static String value(Enum<?> option) {
        return option.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        for (E option : type.getEnumConstants()) {
            if (value(option).equals(value)) {
                return option;
            }
        }
        return fallback;
    }

    private static @Nullable Language language(String code) {
        try {
            return code.isBlank() ? null : Language.fromCode(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
