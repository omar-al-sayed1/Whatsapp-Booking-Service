package app.clinicreminders.dashboard;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicSettings;
import app.clinicreminders.common.Language;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.dashboard.ClinicContext.Ctx;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.scheduling.ScheduleExceptionRepository;
import app.clinicreminders.scheduling.ScheduleRule;
import app.clinicreminders.scheduling.ScheduleRuleRepository;
import app.clinicreminders.scheduling.ScheduleSettings;
import app.clinicreminders.scheduling.TimeWindow;
import app.clinicreminders.staff.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
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
 * Settings (SPEC section 5.3): reminder toggles and the patients' default language on the main
 * page; each doctor's name, slot length, weekly hours and days off on the doctor's page.
 */
@Controller
class SettingsController {

    record HoursRow(Long id, String hours) {
    }

    record DayHours(String day, List<HoursRow> windows) {
    }

    record DayOffRow(Long id, String date, String hours) {
    }

    private final ClinicContext context;
    private final ClinicSettings clinicSettings;
    private final ScheduleSettings scheduleSettings;
    private final DoctorRepository doctors;
    private final ScheduleRuleRepository rules;
    private final ScheduleExceptionRepository exceptions;

    SettingsController(ClinicContext context, ClinicSettings clinicSettings, ScheduleSettings scheduleSettings,
                       DoctorRepository doctors, ScheduleRuleRepository rules, ScheduleExceptionRepository exceptions) {
        this.context = context;
        this.clinicSettings = clinicSettings;
        this.scheduleSettings = scheduleSettings;
        this.doctors = doctors;
        this.rules = rules;
        this.exceptions = exceptions;
    }

    @GetMapping("/settings")
    String settings(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model) {
        Ctx ctx = context.of(staff, request);
        Clinic clinic = ctx.clinic();
        model.addAttribute("defaultLanguage", clinic.getDefaultLanguage().code());
        model.addAttribute("h24Enabled", clinic.isReminderH24Enabled());
        model.addAttribute("h2Enabled", clinic.isReminderH2Enabled());
        model.addAttribute("address", clinic.getAddress());
        model.addAttribute("hoursText", clinic.getHoursText());
        model.addAttribute("contactMax", Clinic.CONTACT_TEXT_MAX);
        model.addAttribute("doctors", doctors.findByClinicIdOrderByDisplayName(ctx.clinicId()));
        return "dashboard/settings";
    }

    @PostMapping("/settings/clinic")
    String saveClinic(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, RedirectAttributes redirect,
                      @RequestParam String defaultLanguage,
                      @RequestParam(defaultValue = "false") boolean h24Enabled,
                      @RequestParam(defaultValue = "false") boolean h2Enabled,
                      @RequestParam(defaultValue = "") String address,
                      @RequestParam(defaultValue = "") String hoursText) {
        try {
            clinicSettings.update(context.of(staff, request).clinicId(), Language.fromCode(defaultLanguage), h24Enabled,
                    h2Enabled, address, hoursText);
            redirect.addFlashAttribute("notice", "notice.saved");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "error.contact");
        }
        return "redirect:/settings";
    }

    @PostMapping("/settings/doctors")
    String addDoctor(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, RedirectAttributes redirect,
                     @RequestParam(defaultValue = "") String displayName, @RequestParam(defaultValue = "0") int slotMinutes) {
        try {
            Doctor doctor = scheduleSettings.addDoctor(context.of(staff, request).clinicId(), displayName, slotMinutes);
            redirect.addFlashAttribute("notice", "notice.saved");
            return "redirect:/settings/doctors/" + doctor.getId();
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "error.doctor");
            return "redirect:/settings";
        }
    }

    @GetMapping("/settings/doctors/{id}")
    String doctor(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model) {
        Ctx ctx = context.of(staff, request);
        Doctor doctor = doctors.findByIdAndClinicId(id, ctx.clinicId()).orElseThrow(() -> new NotFoundException("Doctor", id));
        List<ScheduleRule> doctorRules = rules.findByClinicIdAndDoctorId(ctx.clinicId(), id);
        List<DayHours> week = Arrays.stream(DayOfWeek.values())
                .map(day -> new DayHours(ctx.formats().day(day), doctorRules.stream()
                        .filter(rule -> rule.getDayOfWeek() == day)
                        .sorted(Comparator.comparing(rule -> rule.window().start()))
                        .map(rule -> new HoursRow(rule.getId(), hours(ctx, rule.window())))
                        .toList()))
                .toList();
        List<DayOffRow> daysOff = exceptions
                .findByClinicIdAndDoctorIdAndExceptionDateGreaterThanEqualOrderByExceptionDate(ctx.clinicId(), id, ctx.today())
                .stream()
                .map(e -> new DayOffRow(e.getId(), ctx.formats().date(e.getExceptionDate()),
                        e.hours().map(w -> hours(ctx, w)).orElse(null)))
                .toList();
        model.addAttribute("doctor", doctor);
        model.addAttribute("week", week);
        model.addAttribute("daysOff", daysOff);
        model.addAttribute("weekdays", Arrays.stream(DayOfWeek.values())
                .map(day -> new String[] {day.name(), ctx.formats().day(day)}).toList());
        model.addAttribute("today", ctx.today());
        return "dashboard/doctor-settings";
    }

    @PostMapping("/settings/doctors/{id}")
    String updateDoctor(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                        RedirectAttributes redirect, @RequestParam(defaultValue = "") String displayName,
                        @RequestParam(defaultValue = "0") int slotMinutes,
                        @RequestParam(defaultValue = "false") boolean active) {
        try {
            scheduleSettings.updateDoctor(context.of(staff, request).clinicId(), id, displayName, slotMinutes, active);
            redirect.addFlashAttribute("notice", "notice.saved");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "error.doctor");
        }
        return "redirect:/settings/doctors/" + id;
    }

    @PostMapping("/settings/doctors/{id}/hours")
    String addHours(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                    RedirectAttributes redirect, @RequestParam DayOfWeek day,
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime start,
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime end) {
        try {
            scheduleSettings.addWeeklyHours(context.of(staff, request).clinicId(), id, day, new TimeWindow(start, end));
            redirect.addFlashAttribute("notice", "notice.saved");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "error.hours");
        }
        return "redirect:/settings/doctors/" + id;
    }

    @PostMapping("/settings/hours/{ruleId}/delete")
    String removeHours(@PathVariable long ruleId, @RequestParam long doctorId,
                       @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, RedirectAttributes redirect) {
        scheduleSettings.removeWeeklyHours(context.of(staff, request).clinicId(), ruleId);
        redirect.addFlashAttribute("notice", "notice.saved");
        return "redirect:/settings/doctors/" + doctorId;
    }

    @PostMapping("/settings/doctors/{id}/days")
    String setDay(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                  RedirectAttributes redirect,
                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                  @RequestParam(defaultValue = "false") boolean closed,
                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime start,
                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime end) {
        if (!closed && (start == null || end == null)) {
            redirect.addFlashAttribute("error", "error.hours");
            return "redirect:/settings/doctors/" + id;
        }
        try {
            TimeWindow hours = closed ? null : new TimeWindow(start, end);
            scheduleSettings.setDay(context.of(staff, request).clinicId(), id, date, hours);
            redirect.addFlashAttribute("notice", "notice.saved");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "error.hours");
        }
        return "redirect:/settings/doctors/" + id;
    }

    @PostMapping("/settings/days/{exceptionId}/delete")
    String removeDay(@PathVariable long exceptionId, @RequestParam long doctorId,
                     @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, RedirectAttributes redirect) {
        scheduleSettings.removeDay(context.of(staff, request).clinicId(), exceptionId);
        redirect.addFlashAttribute("notice", "notice.saved");
        return "redirect:/settings/doctors/" + doctorId;
    }

    private static String hours(Ctx ctx, TimeWindow window) {
        return ctx.formats().time(window.start()) + " – " + ctx.formats().time(window.end());
    }
}
