package app.clinicreminders.dashboard;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentService;
import app.clinicreminders.appointment.AppointmentStatus;
import app.clinicreminders.appointment.AppointmentView;
import app.clinicreminders.appointment.SlotTakenException;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.dashboard.ClinicContext.Ctx;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.scheduling.ScheduleRule;
import app.clinicreminders.scheduling.ScheduleRuleRepository;
import app.clinicreminders.scheduling.ScheduleSettings;
import app.clinicreminders.scheduling.Slot;
import app.clinicreminders.scheduling.SlotService;
import app.clinicreminders.scheduling.TimeWindow;
import app.clinicreminders.staff.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The Week page: one doctor, Monday to Sunday. Each day lists the doctor's slots from their
 * opening hours with appointments in place; off-grid appointments (squeeze-ins, old slot lengths)
 * appear at their own times. A free future slot links to the booking form, an appointment to its
 * details, and an open future appointment can be dragged onto a free slot. A closed future day can
 * be opened for that date.
 */
@Controller
class WeekController {

    /** When a day is opened and the doctor has no weekly hours to copy. */
    private static final TimeWindow USUAL_HOURS = new TimeWindow(LocalTime.of(9, 0), LocalTime.of(13, 0));

    /**
     * One line in a day column.
     *
     * @param appointmentId set for booked cells
     * @param movable       booked or confirmed and not started: it can be dragged to a free slot
     */
    record Cell(Instant start, String time, String kind, Long appointmentId, String patientName, String status,
                boolean movable, String bookDate, String bookTime) {
    }

    /**
     * @param date       ISO date, for the forms and the day's anchor
     * @param weekday    "Mon" for the phone's day strip, with {@code dayOfMonth}
     * @param past       before today: nothing can be opened or dropped there
     * @param booked     how many appointments the day has
     */
    record Column(String label, String date, String weekday, int dayOfMonth, boolean today, boolean past, boolean closed,
                  long booked, List<Cell> cells) {
    }

    private final ClinicContext context;
    private final DoctorRepository doctors;
    private final ScheduleRuleRepository rules;
    private final ScheduleSettings scheduleSettings;
    private final SlotService slots;
    private final AppointmentRepository appointments;
    private final AppointmentService appointmentService;

    WeekController(ClinicContext context, DoctorRepository doctors, ScheduleRuleRepository rules,
                   ScheduleSettings scheduleSettings, SlotService slots, AppointmentRepository appointments,
                   AppointmentService appointmentService) {
        this.context = context;
        this.doctors = doctors;
        this.rules = rules;
        this.scheduleSettings = scheduleSettings;
        this.slots = slots;
        this.appointments = appointments;
        this.appointmentService = appointmentService;
    }

    @GetMapping("/week")
    String week(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model,
                @RequestParam(required = false) Long doctorId,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start) {
        Ctx ctx = context.of(staff, request);
        // A doctor's login has only their own week.
        List<Doctor> all = doctors.findByClinicIdOrderByDisplayName(ctx.clinicId()).stream()
                .filter(Doctor::isActive)
                .filter(doctor -> ctx.sees(doctor.getId()))
                .toList();
        LocalDate monday = mondayOf(start != null ? start : ctx.today());
        model.addAttribute("doctors", all);
        model.addAttribute("monday", monday);
        model.addAttribute("previousStart", monday.minusWeeks(1));
        model.addAttribute("nextStart", monday.plusWeeks(1));
        model.addAttribute("thisWeek", monday.equals(mondayOf(ctx.today())));
        model.addAttribute("weekLabel", ctx.formats().dayShort(monday) + " – " + ctx.formats().dayShort(monday.plusDays(6)));
        Doctor doctor = all.stream().filter(d -> d.getId().equals(doctorId)).findFirst()
                .orElse(all.isEmpty() ? null : all.getFirst());
        model.addAttribute("doctor", doctor);
        model.addAttribute("columns", doctor == null ? List.of() : columns(ctx, doctor, monday));
        TimeWindow usual = doctor == null ? USUAL_HOURS : usualHours(ctx, doctor);
        model.addAttribute("openStart", usual.start().toString());
        model.addAttribute("openEnd", usual.end().toString());
        model.addAttribute("focusDay", focusDay(model.getAttribute("focusDay"), monday, ctx.today()));
        return "dashboard/week";
    }

    /** Opens a closed day for this date only, like special hours in Settings. */
    @PostMapping("/week/open-day")
    String openDay(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, RedirectAttributes redirect,
                   @RequestParam long doctorId,
                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime start,
                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime end) {
        Ctx ctx = context.of(staff, request);
        ctx.requireSees(doctorId, "Doctor", doctorId);
        if (date.isBefore(ctx.today())) {
            redirect.addFlashAttribute("error", "error.not-allowed");
        } else {
            try {
                scheduleSettings.setDay(ctx.clinicId(), doctorId, date, new TimeWindow(start, end));
                redirect.addFlashAttribute("notice", "notice.day-opened");
            } catch (IllegalArgumentException e) {
                redirect.addFlashAttribute("error", "error.hours");
            }
        }
        return redirectToWeek(redirect, doctorId, date);
    }

    /** Drag and drop: moves an open appointment to the slot it was dropped on, keeping its length. */
    @PostMapping("/week/move")
    String move(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, RedirectAttributes redirect,
                @RequestParam long appointmentId,
                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime time) {
        Ctx ctx = context.of(staff, request);
        Appointment appointment = appointments.findByIdAndClinicId(appointmentId, ctx.clinicId())
                .orElseThrow(() -> new NotFoundException("Appointment", appointmentId));
        ctx.requireSees(appointment.getDoctorId(), "Appointment", appointmentId);
        Instant newStart = ctx.instant(date, time);
        if (!isMovable(appointment, ctx.now()) || !newStart.isAfter(ctx.now())) {
            redirect.addFlashAttribute("error", "error.not-allowed");
            return redirectToWeek(redirect, appointment.getDoctorId(), ctx.dateOf(appointment.getStartsAt()));
        }
        try {
            appointmentService.reschedule(ctx.clinicId(), appointmentId, newStart);
            redirect.addFlashAttribute("notice", "notice.moved");
        } catch (SlotTakenException e) {
            redirect.addFlashAttribute("error", "error.slot-taken");
        }
        return redirectToWeek(redirect, appointment.getDoctorId(), date);
    }

    /** Back to the week of that date; on a phone (one day under another) the page lands on that day. */
    static String redirectToWeek(RedirectAttributes redirect, long doctorId, LocalDate date) {
        redirect.addFlashAttribute("focusDay", date.toString());
        return "redirect:/week?doctorId=" + doctorId + "&start=" + mondayOf(date);
    }

    /**
     * The day a phone scrolls to: the one just changed (flashed by {@link #redirectToWeek}) if it is
     * in this week, otherwise today when this is the current week, otherwise none (the top).
     */
    static @Nullable String focusDay(@Nullable Object flashed, LocalDate monday, LocalDate today) {
        LocalDate sunday = monday.plusDays(6);
        if (flashed instanceof String day) {
            try {
                LocalDate date = LocalDate.parse(day);
                if (!date.isBefore(monday) && !date.isAfter(sunday)) {
                    return day;
                }
            } catch (DateTimeParseException e) {
                // not a date: fall through to today
            }
        }
        return today.isBefore(monday) || today.isAfter(sunday) ? null : today.toString();
    }

    /** Booked or confirmed, and not started yet. */
    static boolean isMovable(Appointment appointment, Instant now) {
        boolean open = appointment.getStatus() == AppointmentStatus.BOOKED
                || appointment.getStatus() == AppointmentStatus.CONFIRMED;
        return open && appointment.getStartsAt().isAfter(now);
    }

    private static LocalDate mondayOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** The doctor's earliest weekly window, as the suggestion for opening a closed day. */
    private TimeWindow usualHours(Ctx ctx, Doctor doctor) {
        return rules.findByClinicIdAndDoctorId(ctx.clinicId(), doctor.getId()).stream()
                .map(ScheduleRule::window)
                .min(Comparator.comparing(TimeWindow::start))
                .orElse(USUAL_HOURS);
    }

    private List<Column> columns(Ctx ctx, Doctor doctor, LocalDate monday) {
        LocalDate sunday = monday.plusDays(6);
        List<Slot> open = slots.allSlots(ctx.clinicId(), doctor.getId(), monday, sunday);
        List<AppointmentView> booked = appointments.findViews(ctx.clinicId(), ctx.startOf(monday), ctx.startOf(sunday.plusDays(1)))
                .stream()
                .filter(view -> view.doctorId().equals(doctor.getId()) && view.isActive())
                .toList();
        List<Column> columns = new ArrayList<>();
        for (LocalDate day = monday; !day.isAfter(sunday); day = day.plusDays(1)) {
            LocalDate date = day;
            List<Cell> cells = new ArrayList<>();
            List<AppointmentView> dayAppointments = booked.stream().filter(v -> ctx.dateOf(v.startsAt()).equals(date)).toList();
            for (AppointmentView view : dayAppointments) {
                boolean movable = (view.status() == AppointmentStatus.BOOKED || view.status() == AppointmentStatus.CONFIRMED)
                        && view.startsAt().isAfter(ctx.now());
                cells.add(new Cell(view.startsAt(), ctx.formats().time(view.startsAt()), "booked", view.id(),
                        view.patientName(), view.status().name(), movable, null, null));
            }
            for (Slot slot : open) {
                if (!ctx.dateOf(slot.start()).equals(date)) {
                    continue;
                }
                boolean taken = dayAppointments.stream()
                        .anyMatch(v -> v.startsAt().isBefore(slot.end()) && slot.start().isBefore(v.endsAt()));
                if (!taken) {
                    boolean past = !slot.start().isAfter(ctx.now());
                    cells.add(new Cell(slot.start(), ctx.formats().time(slot.start()), past ? "past" : "free", null, null,
                            null, false, date.toString(), ctx.timeOf(slot.start()).toString()));
                }
            }
            cells.sort(Comparator.comparing(Cell::start));
            columns.add(new Column(ctx.formats().dayShort(date), date.toString(), ctx.formats().weekdayShort(date),
                    date.getDayOfMonth(), date.equals(ctx.today()), date.isBefore(ctx.today()), cells.isEmpty(),
                    dayAppointments.size(), cells));
        }
        return columns;
    }
}
