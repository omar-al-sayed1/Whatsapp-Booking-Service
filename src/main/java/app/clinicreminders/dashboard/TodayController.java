package app.clinicreminders.dashboard;

import java.time.LocalDate;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentEvents.CancelledBy;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentService;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.dashboard.ClinicContext.Ctx;
import app.clinicreminders.staff.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The Today page (SPEC section 5.3): the day's appointments per doctor, refreshed every 30 seconds,
 * and the arrived / no-show / cancel actions. With htmx the actions return the refreshed list;
 * without it they redirect back to the page.
 */
@Controller
class TodayController {

    private final ClinicContext context;
    private final TodayList list;
    private final AppointmentService appointmentService;
    private final AppointmentRepository appointments;

    TodayController(ClinicContext context, TodayList list, AppointmentService appointmentService,
                    AppointmentRepository appointments) {
        this.context = context;
        this.list = list;
        this.appointmentService = appointmentService;
        this.appointments = appointments;
    }

    @GetMapping("/")
    String today(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model,
                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (staff.clinicId() == null) {
            return "redirect:/operator";
        }
        Ctx ctx = context.of(staff, request);
        LocalDate day = date != null ? date : ctx.today();
        list.fill(ctx, day, model);
        model.addAttribute("dateLabel", ctx.formats().date(day));
        model.addAttribute("isToday", day.equals(ctx.today()));
        model.addAttribute("previousDate", day.minusDays(1));
        model.addAttribute("nextDate", day.plusDays(1));
        return "dashboard/today";
    }

    @GetMapping("/today/list")
    String refresh(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model,
                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        list.fill(context.of(staff, request), date, model);
        return "dashboard/today-list :: list";
    }

    @PostMapping("/appointments/{id}/arrived")
    String arrived(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                   Model model, RedirectAttributes redirect, @RequestHeader(name = "HX-Request", required = false) String htmx) {
        Ctx ctx = context.of(staff, request);
        appointmentService.markArrived(ctx.clinicId(), id);
        return respond(ctx, id, model, redirect, htmx);
    }

    @PostMapping("/appointments/{id}/no-show")
    String noShow(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                  Model model, RedirectAttributes redirect, @RequestHeader(name = "HX-Request", required = false) String htmx) {
        Ctx ctx = context.of(staff, request);
        appointmentService.markNoShow(ctx.clinicId(), id);
        return respond(ctx, id, model, redirect, htmx);
    }

    /** Staff cancellations don't message the patient (that would need its own approved template). */
    @PostMapping("/appointments/{id}/cancel")
    String cancel(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                  Model model, RedirectAttributes redirect, @RequestHeader(name = "HX-Request", required = false) String htmx) {
        Ctx ctx = context.of(staff, request);
        appointmentService.cancel(ctx.clinicId(), id, CancelledBy.STAFF);
        return respond(ctx, id, model, redirect, htmx);
    }

    private String respond(Ctx ctx, long appointmentId, Model model, RedirectAttributes redirect, String htmx) {
        Appointment appointment = appointments.findByIdAndClinicId(appointmentId, ctx.clinicId())
                .orElseThrow(() -> new NotFoundException("Appointment", appointmentId));
        LocalDate date = ctx.dateOf(appointment.getStartsAt());
        if ("true".equals(htmx)) {
            list.fill(ctx, date, model);
            return "dashboard/today-list :: list";
        }
        redirect.addAttribute("date", date);
        return "redirect:/";
    }
}
