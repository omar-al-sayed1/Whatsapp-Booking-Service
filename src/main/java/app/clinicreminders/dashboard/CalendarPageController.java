package app.clinicreminders.dashboard;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentStatus;
import app.clinicreminders.appointment.CalendarFile;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicSettings;
import app.clinicreminders.common.Language;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.MessageSource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * "Add to my calendar", for patients, without logging in: the link in the WhatsApp booking
 * confirmation (or sent by the secretary) opens a page with the appointment and two buttons, the
 * calendar file (phones, Outlook) and Google Calendar. The random token in the link is the only key;
 * the page shows the clinic, doctor and time, never the patient. In the patient's language; never
 * indexed by search engines.
 */
@Controller
class CalendarPageController {

    private static final DateTimeFormatter GOOGLE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** The page, as the template shows it. */
    record Details(String clinic, String doctor, String date, String time, String address, boolean cancelled,
                   boolean past, String calendarFile, String googleCalendar) {
    }

    private final AppointmentRepository appointments;
    private final ClinicSettings clinics;
    private final DoctorRepository doctors;
    private final PatientRepository patients;
    private final MessageSource messages;
    private final Clock clock;

    CalendarPageController(AppointmentRepository appointments, ClinicSettings clinics, DoctorRepository doctors,
                           PatientRepository patients, MessageSource messages, Clock clock) {
        this.appointments = appointments;
        this.clinics = clinics;
        this.doctors = doctors;
        this.patients = patients;
        this.messages = messages;
        this.clock = clock;
    }

    @GetMapping("/c/{token}")
    String page(@PathVariable String token, HttpServletRequest request, HttpServletResponse response, Model model) {
        Event event = event(token);
        Language language = event.language();
        StaffLocaleResolver.forRequest(request, language);
        model.addAttribute("lang", language.code());
        model.addAttribute("dir", language == Language.AR ? "rtl" : "ltr");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        Locale locale = language.locale();
        ZoneId zone = event.clinic().getTimezone();
        Appointment appointment = event.appointment();
        CalendarFile file = event.file();
        String google = UriComponentsBuilder.fromUriString("https://calendar.google.com/calendar/render")
                .queryParam("action", "TEMPLATE")
                .queryParam("text", file.summary())
                .queryParam("dates", GOOGLE.format(file.start()) + "/" + GOOGLE.format(file.end()))
                .queryParamIfPresent("location", Optional.ofNullable(file.location()).filter(l -> !l.isBlank()))
                .queryParam("details", file.description())
                .encode()
                .toUriString();
        model.addAttribute("details", new Details(event.clinic().getName(), event.doctor().getDisplayName(),
                format(appointment.getStartsAt(), zone, "patient.date-format", locale),
                format(appointment.getStartsAt(), zone, "patient.time-format", locale),
                event.clinic().getAddress(), file.cancelled(), !appointment.getStartsAt().isAfter(clock.instant()),
                "/c/" + appointment.getCalendarToken() + "/calendar.ics", google));
        return "dashboard/calendar";
    }

    @GetMapping("/c/{token}/calendar.ics")
    ResponseEntity<String> file(@PathVariable String token) {
        Event event = event(token);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "calendar", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("appointment.ics").build().toString())
                .header("X-Robots-Tag", "noindex, nofollow")
                .body(event.file().render(clock.instant()));
    }

    private record Event(Appointment appointment, Clinic clinic, Doctor doctor, Language language, CalendarFile file) {
    }

    /** The appointment behind the token, in its patient's language (the clinic's once the patient is deleted). */
    private Event event(String token) {
        UUID key;
        try {
            key = UUID.fromString(token);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Calendar link", token);
        }
        Appointment appointment = appointments.findByCalendarToken(key)
                .orElseThrow(() -> new NotFoundException("Calendar link", token));
        long clinicId = appointment.getClinicId();
        Clinic clinic = clinics.get(clinicId);
        Doctor doctor = doctors.findByIdAndClinicId(appointment.getDoctorId(), clinicId)
                .orElseThrow(() -> new NotFoundException("Doctor", appointment.getDoctorId()));
        Language language = appointment.getPatientId() == null ? clinic.getDefaultLanguage()
                : patients.findByIdAndClinicId(appointment.getPatientId(), clinicId)
                        .map(patient -> patient.getLanguage()).orElse(clinic.getDefaultLanguage());
        Locale locale = language.locale();
        CalendarFile file = new CalendarFile(appointment.getCalendarToken(), appointment.getStartsAt(),
                appointment.getEndsAt(), doctor.getDisplayName() + " · " + clinic.getName(), clinic.getAddress(),
                messages.getMessage("calendar.change", null, locale),
                messages.getMessage("calendar.alarm", new Object[] {doctor.getDisplayName()}, locale),
                appointment.getStatus() == AppointmentStatus.CANCELLED);
        return new Event(appointment, clinic, doctor, language, file);
    }

    private String format(Instant instant, ZoneId zone, String patternKey, Locale locale) {
        return DateTimeFormatter.ofPattern(messages.getMessage(patternKey, null, locale), locale).format(instant.atZone(zone));
    }
}
