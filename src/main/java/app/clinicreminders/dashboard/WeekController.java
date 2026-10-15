package app.clinicreminders.dashboard;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentView;
import app.clinicreminders.dashboard.ClinicContext.Ctx;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.scheduling.Slot;
import app.clinicreminders.scheduling.SlotService;
import app.clinicreminders.staff.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The Week page: one doctor, Monday to Sunday. Each day lists the doctor's slots from their
 * opening hours with appointments in place; off-grid appointments (squeeze-ins, old slot lengths)
 * appear at their own times. A free future slot links to the booking form.
 */
@Controller
class WeekController {

    /** One line in a day column. */
    record Cell(Instant start, String time, String kind, String patientName, String status, String bookDate,
                String bookTime) {
    }

    record Column(String label, boolean today, boolean closed, List<Cell> cells) {
    }

    private final ClinicContext context;
    private final DoctorRepository doctors;
    private final SlotService slots;
    private final AppointmentRepository appointments;

    WeekController(ClinicContext context, DoctorRepository doctors, SlotService slots, AppointmentRepository appointments) {
        this.context = context;
        this.doctors = doctors;
        this.slots = slots;
        this.appointments = appointments;
    }

    @GetMapping("/week")
    String week(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model,
                @RequestParam(required = false) Long doctorId,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start) {
        Ctx ctx = context.of(staff, request);
        List<Doctor> all = doctors.findByClinicIdOrderByDisplayName(ctx.clinicId()).stream().filter(Doctor::isActive).toList();
        LocalDate monday = (start != null ? start : ctx.today()).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        model.addAttribute("doctors", all);
        model.addAttribute("monday", monday);
        model.addAttribute("previousStart", monday.minusWeeks(1));
        model.addAttribute("nextStart", monday.plusWeeks(1));
        model.addAttribute("weekLabel", ctx.formats().dayShort(monday) + " – " + ctx.formats().dayShort(monday.plusDays(6)));
        Doctor doctor = all.stream().filter(d -> d.getId().equals(doctorId)).findFirst()
                .orElse(all.isEmpty() ? null : all.getFirst());
        model.addAttribute("doctor", doctor);
        model.addAttribute("columns", doctor == null ? List.of() : columns(ctx, doctor, monday));
        return "dashboard/week";
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
                cells.add(new Cell(view.startsAt(), ctx.formats().time(view.startsAt()), "booked", view.patientName(),
                        view.status().name(), null, null));
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
                            date.toString(), ctx.timeOf(slot.start()).toString()));
                }
            }
            cells.sort(Comparator.comparing(Cell::start));
            columns.add(new Column(ctx.formats().dayShort(date), date.equals(ctx.today()), cells.isEmpty(), cells));
        }
        return columns;
    }
}
