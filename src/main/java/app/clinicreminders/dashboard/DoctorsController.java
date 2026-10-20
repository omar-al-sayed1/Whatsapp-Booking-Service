package app.clinicreminders.dashboard;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.dashboard.ClinicContext.Ctx;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.scheduling.ScheduleExceptionRepository;
import app.clinicreminders.scheduling.ScheduleRule;
import app.clinicreminders.scheduling.ScheduleRuleRepository;
import app.clinicreminders.scheduling.ScheduleSettings;
import app.clinicreminders.scheduling.TimeWindow;
import app.clinicreminders.staff.StaffAccounts;
import app.clinicreminders.staff.StaffPrincipal;
import app.clinicreminders.staff.UsernameTakenException;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Doctors (SPEC section 5.3): the list with each doctor's working days and the form to add one,
 * with a login if asked; each doctor's page has their name, weekly hours, days off and login. The
 * appointment length is the clinic's, set by the operator. A doctor's own login gets only their own
 * page, to change their hours and days off ("My hours"); the rest is the front desk's (SecurityConfig).
 *
 * <p>A day off or special hours that leave out patients already booked go through a review page
 * first: it lists them, and the appointments are cancelled (the patients are messaged) or kept.
 */
@Controller
class DoctorsController {

    /**
     * One opening window, placed on the day bar (6:00 to 22:00): {@code left} and {@code width} are
     * percentages of the bar.
     */
    record HoursRow(Long id, String hours, String left, String width) {
    }

    record DayHours(String day, List<HoursRow> windows) {
    }

    record DayOffRow(Long id, String date, String hours, String dayOfMonth, String month) {
    }

    /** An appointment a day change would leave out, as the review lists it; no patient if they were deleted. */
    record LeftOutRow(String time, @Nullable String patient, @Nullable String phone, boolean optedOut) {
    }

    /** A doctor in the list, with the weekdays they have hours on. */
    record DoctorRow(Doctor doctor, Set<DayOfWeek> workingDays) {
    }

    /** Monday to Sunday with the locale's one-letter names, for the small week strips. */
    record Weekday(DayOfWeek day, String letter) {
    }

    private static final LocalTime BAR_START = LocalTime.of(6, 0);
    private static final int BAR_MINUTES = 16 * 60;

    private final ClinicContext context;
    private final ScheduleSettings scheduleSettings;
    private final DoctorSetup doctorSetup;
    private final StaffAccounts accounts;
    private final DoctorRepository doctors;
    private final ScheduleRuleRepository rules;
    private final ScheduleExceptionRepository exceptions;
    private final AppointmentRepository appointments;
    private final DayChanges dayChanges;
    private final PatientRepository patients;

    DoctorsController(ClinicContext context, ScheduleSettings scheduleSettings, DoctorSetup doctorSetup,
                      StaffAccounts accounts, DoctorRepository doctors, ScheduleRuleRepository rules,
                      ScheduleExceptionRepository exceptions, AppointmentRepository appointments,
                      DayChanges dayChanges, PatientRepository patients) {
        this.context = context;
        this.scheduleSettings = scheduleSettings;
        this.doctorSetup = doctorSetup;
        this.accounts = accounts;
        this.doctors = doctors;
        this.rules = rules;
        this.exceptions = exceptions;
        this.appointments = appointments;
        this.dayChanges = dayChanges;
        this.patients = patients;
    }

    /** For a doctor's login, their own page. */
    @GetMapping("/doctors")
    String list(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model) {
        Ctx ctx = context.of(staff, request);
        if (ctx.isDoctor()) {
            return "redirect:/doctors/" + ctx.doctorId();
        }
        model.addAttribute("doctors", doctors.findByClinicIdOrderByDisplayName(ctx.clinicId()).stream()
                .filter(doctor -> !doctor.isDeleted())
                .map(doctor -> new DoctorRow(doctor, rules.findByClinicIdAndDoctorId(ctx.clinicId(), doctor.getId()).stream()
                        .map(ScheduleRule::getDayOfWeek)
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)))))
                .toList());
        model.addAttribute("weekdays", Arrays.stream(DayOfWeek.values())
                .map(day -> new Weekday(day, day.getDisplayName(TextStyle.NARROW, ctx.language().locale())))
                .toList());
        model.addAttribute("slotMinutes", ctx.clinic().getSlotMinutes());
        model.addAttribute("minPassword", StaffAccounts.MIN_PASSWORD_LENGTH);
        return "dashboard/doctors";
    }

    /**
     * Adds a doctor; with {@code addLogin}, also their login with the username and password typed
     * here (in the clinic's language; the doctor can switch it). Nothing is added if either is refused.
     */
    @PostMapping("/doctors")
    String add(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, RedirectAttributes redirect,
               @RequestParam(defaultValue = "") String displayName,
               @RequestParam(defaultValue = "false") boolean addLogin,
               @RequestParam(defaultValue = "") String username, @RequestParam(defaultValue = "") String password) {
        Ctx ctx = context.of(staff, request);
        String error = displayName.isBlank() ? "error.doctor" : addLogin ? loginError(username, password) : null;
        if (error == null) {
            try {
                Doctor doctor = doctorSetup.add(ctx.clinicId(), displayName, addLogin
                        ? new DoctorSetup.Login(username, password, ctx.clinic().getDefaultLanguage()) : null);
                redirect.addFlashAttribute("notice", addLogin ? "notice.doctor-login-added" : "notice.saved");
                return "redirect:/doctors/" + doctor.getId();
            } catch (UsernameTakenException e) {
                error = "error.username-taken";
            } catch (IllegalArgumentException e) {
                error = "error.doctor";
            }
        }
        // Back to the form with what was typed, except the password.
        redirect.addFlashAttribute("error", error);
        redirect.addFlashAttribute("draftName", displayName);
        redirect.addFlashAttribute("draftUsername", username);
        redirect.addFlashAttribute("draftAddLogin", addLogin);
        return "redirect:/doctors";
    }

    @GetMapping("/doctors/{id}")
    String doctor(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model) {
        Ctx ctx = context.of(staff, request);
        Doctor doctor = doctor(ctx, id);
        List<ScheduleRule> doctorRules = rules.findByClinicIdAndDoctorId(ctx.clinicId(), id);
        List<DayHours> week = Arrays.stream(DayOfWeek.values())
                .map(day -> new DayHours(ctx.formats().day(day), doctorRules.stream()
                        .filter(rule -> rule.getDayOfWeek() == day)
                        .sorted(Comparator.comparing(rule -> rule.window().start()))
                        .map(rule -> hoursRow(ctx, rule.getId(), rule.window()))
                        .toList()))
                .toList();
        List<DayOffRow> daysOff = exceptions
                .findByClinicIdAndDoctorIdAndExceptionDateGreaterThanEqualOrderByExceptionDate(ctx.clinicId(), id, ctx.today())
                .stream()
                .map(e -> new DayOffRow(e.getId(), ctx.formats().date(e.getExceptionDate()),
                        e.hours().map(w -> hours(ctx, w)).orElse(null),
                        String.valueOf(e.getExceptionDate().getDayOfMonth()),
                        e.getExceptionDate().getMonth().getDisplayName(TextStyle.SHORT, ctx.language().locale())))
                .toList();
        model.addAttribute("doctor", doctor);
        model.addAttribute("week", week);
        model.addAttribute("daysOff", daysOff);
        model.addAttribute("weekdays", Arrays.stream(DayOfWeek.values())
                .map(day -> new String[] {day.name(), ctx.formats().day(day)}).toList());
        model.addAttribute("today", ctx.today());
        // A doctor on their own page ("My hours") sees hours and days off; the front desk also the profile and login.
        model.addAttribute("ownPage", ctx.isDoctor());
        if (!ctx.isDoctor()) {
            model.addAttribute("login", accounts.doctorLogin(ctx.clinicId(), id).orElse(null));
            model.addAttribute("minPassword", StaffAccounts.MIN_PASSWORD_LENGTH);
            model.addAttribute("upcoming", appointments.countUpcomingOfDoctor(ctx.clinicId(), id, ctx.now()));
        }
        return "dashboard/doctor-settings";
    }

    /** Front desk, after a confirmation; refused while the doctor still has appointments to come. */
    @PostMapping("/doctors/{id}/delete")
    String delete(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                  RedirectAttributes redirect) {
        Ctx ctx = context.of(staff, request);
        doctor(ctx, id);
        if (!doctorSetup.delete(ctx.clinicId(), id, ctx.now())) {
            redirect.addFlashAttribute("error", "error.doctor-has-appointments");
            return "redirect:/doctors/" + id;
        }
        redirect.addFlashAttribute("notice", "notice.doctor-deleted");
        return "redirect:/doctors";
    }

    /** A login for a doctor who has none, with the username and password typed by the owner or secretary. */
    @PostMapping("/doctors/{id}/login")
    String addLogin(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                    RedirectAttributes redirect, @RequestParam(defaultValue = "") String username,
                    @RequestParam(defaultValue = "") String password) {
        Ctx ctx = context.of(staff, request);
        doctor(ctx, id);
        String error = accounts.doctorLogin(ctx.clinicId(), id).isPresent() ? "error.not-allowed" : loginError(username, password);
        if (error == null) {
            try {
                accounts.createDoctorLogin(ctx.clinicId(), id, username, password, ctx.clinic().getDefaultLanguage());
                redirect.addFlashAttribute("notice", "notice.doctor-login-added");
                return "redirect:/doctors/" + id;
            } catch (UsernameTakenException e) {
                error = "error.username-taken";
            } catch (IllegalArgumentException e) {
                error = "error.username";
            }
        }
        redirect.addFlashAttribute("error", error);
        redirect.addFlashAttribute("draftUsername", username);
        return "redirect:/doctors/" + id;
    }

    /** A new password for the doctor's login, typed by the owner or secretary to hand over. */
    @PostMapping("/doctors/{id}/login/password")
    String setLoginPassword(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                            RedirectAttributes redirect, @RequestParam(defaultValue = "") String password) {
        Ctx ctx = context.of(staff, request);
        doctor(ctx, id);
        if (!StaffAccounts.isLongEnough(password)) {
            redirect.addFlashAttribute("error", "error.password-short");
        } else {
            accounts.setDoctorPassword(ctx.clinicId(), id, password);
            redirect.addFlashAttribute("notice", "notice.doctor-password-set");
        }
        return "redirect:/doctors/" + id;
    }

    /** A disabled login can't sign in; turning it back on keeps its password. */
    @PostMapping("/doctors/{id}/login/enabled")
    String setLoginEnabled(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                           RedirectAttributes redirect, @RequestParam boolean enabled) {
        Ctx ctx = context.of(staff, request);
        doctor(ctx, id);
        accounts.setDoctorLoginEnabled(ctx.clinicId(), id, enabled);
        redirect.addFlashAttribute("notice", "notice.saved");
        return "redirect:/doctors/" + id;
    }

    @PostMapping("/doctors/{id}")
    String update(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                  RedirectAttributes redirect, @RequestParam(defaultValue = "") String displayName,
                  @RequestParam(defaultValue = "false") boolean active) {
        try {
            scheduleSettings.updateDoctor(context.of(staff, request).clinicId(), id, displayName, active);
            redirect.addFlashAttribute("notice", "notice.saved");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "error.doctor");
        }
        return "redirect:/doctors/" + id;
    }

    @PostMapping("/doctors/{id}/hours")
    String addHours(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                    RedirectAttributes redirect, @RequestParam DayOfWeek day,
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime start,
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime end) {
        Ctx ctx = context.of(staff, request);
        ctx.requireSees(id, "Doctor", id);
        try {
            scheduleSettings.addWeeklyHours(ctx.clinicId(), id, day, new TimeWindow(start, end));
            redirect.addFlashAttribute("notice", "notice.saved");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "error.hours");
        }
        return "redirect:/doctors/" + id;
    }

    /** Only that doctor's hours, in the user's clinic; others are not found. */
    @PostMapping("/doctors/{id}/hours/{ruleId}/delete")
    String removeHours(@PathVariable long id, @PathVariable long ruleId,
                       @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, RedirectAttributes redirect) {
        Ctx ctx = context.of(staff, request);
        ctx.requireSees(id, "Doctor", id);
        scheduleSettings.removeWeeklyHours(ctx.clinicId(), id, ruleId);
        redirect.addFlashAttribute("notice", "notice.saved");
        return "redirect:/doctors/" + id;
    }

    /**
     * Saves a day off or special hours. When patients are booked in the hours it takes away, nothing
     * changes until {@code appointments} says what happens to them: "cancel" (they are messaged to
     * book another time) or "keep"; without it, the review page lists them first.
     */
    @PostMapping("/doctors/{id}/days")
    String setDay(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                  RedirectAttributes redirect,
                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                  @RequestParam(defaultValue = "false") boolean closed,
                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime start,
                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime end,
                  @RequestParam(defaultValue = "") String appointments) {
        Ctx ctx = context.of(staff, request);
        ctx.requireSees(id, "Doctor", id);
        TimeWindow hours;
        try {
            hours = dayHours(closed, start, end);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "error.hours");
            return "redirect:/doctors/" + id;
        }
        boolean cancel = appointments.equals("cancel");
        boolean decided = cancel || appointments.equals("keep");
        List<Appointment> leftOut = dayChanges.leftOut(ctx.clinicId(), id, date, hours, ctx.zone(), ctx.now());
        if (!leftOut.isEmpty() && !decided) {
            redirect.addAttribute("date", date);
            redirect.addAttribute("closed", closed);
            if (hours != null) {
                redirect.addAttribute("start", hours.start().toString());
                redirect.addAttribute("end", hours.end().toString());
            }
            return "redirect:/doctors/" + id + "/days/review";
        }
        int cancelled = dayChanges.apply(ctx.clinicId(), id, date, hours, cancel, ctx.zone(), ctx.now());
        redirect.addFlashAttribute("notice", leftOut.isEmpty() ? "notice.saved"
                : cancelled > 0 ? "notice.day-cancelled" : "notice.day-kept");
        return "redirect:/doctors/" + id;
    }

    /** Before a day change that leaves out booked patients: who they are, and cancel or keep their appointments. */
    @GetMapping("/doctors/{id}/days/review")
    String reviewDay(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                     Model model, RedirectAttributes redirect,
                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                     @RequestParam(defaultValue = "false") boolean closed,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime start,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime end) {
        Ctx ctx = context.of(staff, request);
        Doctor doctor = doctor(ctx, id);
        TimeWindow hours;
        try {
            hours = dayHours(closed, start, end);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "error.hours");
            return "redirect:/doctors/" + id;
        }
        model.addAttribute("doctor", doctor);
        model.addAttribute("date", date);
        model.addAttribute("dateLabel", ctx.formats().date(date));
        model.addAttribute("closed", hours == null);
        model.addAttribute("start", hours == null ? null : hours.start());
        model.addAttribute("end", hours == null ? null : hours.end());
        model.addAttribute("hoursLabel", hours == null ? null : hours(ctx, hours));
        model.addAttribute("leftOut", dayChanges.leftOut(ctx.clinicId(), id, date, hours, ctx.zone(), ctx.now()).stream()
                .map(a -> {
                    Patient patient = a.getPatientId() == null ? null
                            : patients.findByIdAndClinicId(a.getPatientId(), ctx.clinicId()).orElse(null);
                    return new LeftOutRow(ctx.formats().time(a.getStartsAt()), patient == null ? null : patient.getName(),
                            patient == null ? null : patient.getPhoneE164(), patient != null && patient.isOptedOut());
                })
                .toList());
        return "dashboard/day-review";
    }

    /** Null for a day off; the special hours otherwise. */
    private static @Nullable TimeWindow dayHours(boolean closed, @Nullable LocalTime start, @Nullable LocalTime end) {
        if (closed) {
            return null;
        }
        if (start == null || end == null) {
            throw new IllegalArgumentException("Special hours need a start and an end");
        }
        return new TimeWindow(start, end);
    }

    /** As for hours: only that doctor's day, in the user's clinic. */
    @PostMapping("/doctors/{id}/days/{exceptionId}/delete")
    String removeDay(@PathVariable long id, @PathVariable long exceptionId,
                     @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, RedirectAttributes redirect) {
        Ctx ctx = context.of(staff, request);
        ctx.requireSees(id, "Doctor", id);
        scheduleSettings.removeDay(ctx.clinicId(), id, exceptionId);
        redirect.addFlashAttribute("notice", "notice.saved");
        return "redirect:/doctors/" + id;
    }

    /** The clinic's doctor, if this user may see them (a doctor's login: only themself); a deleted one is not found. */
    private Doctor doctor(Ctx ctx, long id) {
        ctx.requireSees(id, "Doctor", id);
        return doctors.findByIdAndClinicId(id, ctx.clinicId())
                .filter(doctor -> !doctor.isDeleted())
                .orElseThrow(() -> new NotFoundException("Doctor", id));
    }

    /** What's wrong with a login typed for a doctor (before asking whether the username is free), or null. */
    private static @Nullable String loginError(String username, String password) {
        if (!StaffAccounts.isValidUsername(username)) {
            return "error.username";
        }
        return StaffAccounts.isLongEnough(password) ? null : "error.password-short";
    }

    static HoursRow hoursRow(Ctx ctx, Long id, TimeWindow window) {
        int start = clampToBar(window.start());
        int end = window.end().equals(LocalTime.MIDNIGHT) ? BAR_MINUTES : clampToBar(window.end());
        return new HoursRow(id, hours(ctx, window), percent(start), percent(Math.max(end - start, 0)));
    }

    private static int clampToBar(LocalTime time) {
        int minutes = time.toSecondOfDay() / 60 - BAR_START.toSecondOfDay() / 60;
        return Math.clamp(minutes, 0, BAR_MINUTES);
    }

    /** As CSS wants it: a dot, whatever the locale. */
    private static String percent(int minutes) {
        return String.format(Locale.ROOT, "%.2f", 100.0 * minutes / BAR_MINUTES);
    }

    private static String hours(Ctx ctx, TimeWindow window) {
        return ctx.formats().time(window.start()) + " – " + ctx.formats().time(window.end());
    }
}
