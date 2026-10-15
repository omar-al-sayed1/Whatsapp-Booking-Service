package app.clinicreminders.dashboard;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import app.clinicreminders.appointment.AppointmentEvents.CancelledBy;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentStatus;
import app.clinicreminders.appointment.AppointmentView;
import app.clinicreminders.dashboard.ClinicContext.Ctx;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/** Builds the day's appointment list, per doctor (the Today page and its 30-second refresh). */
@Component
class TodayList {

    /** One appointment as listed, with the actions that make sense for it right now. */
    record Row(Long id, String time, String patientName, String patientPhone, String status, String source,
               boolean cancelled, boolean cancelledByPatient,
               boolean canArrive, boolean canNoShow, boolean canCancel, boolean canReschedule) {
    }

    record DoctorDay(Long id, String name, List<Row> rows) {
    }

    private final DoctorRepository doctors;
    private final AppointmentRepository appointments;

    TodayList(DoctorRepository doctors, AppointmentRepository appointments) {
        this.doctors = doctors;
        this.appointments = appointments;
    }

    void fill(Ctx ctx, LocalDate date, Model model) {
        Map<Long, List<AppointmentView>> byDoctor = appointments
                .findViews(ctx.clinicId(), ctx.startOf(date), ctx.startOf(date.plusDays(1))).stream()
                .collect(Collectors.groupingBy(AppointmentView::doctorId));
        List<DoctorDay> days = doctors.findByClinicIdOrderByDisplayName(ctx.clinicId()).stream()
                .filter(doctor -> doctor.isActive() || byDoctor.containsKey(doctor.getId()))
                .map(doctor -> day(ctx, date, doctor, byDoctor.getOrDefault(doctor.getId(), List.of())))
                .toList();
        model.addAttribute("doctors", days);
        model.addAttribute("date", date);
    }

    private static DoctorDay day(Ctx ctx, LocalDate date, Doctor doctor, List<AppointmentView> views) {
        return new DoctorDay(doctor.getId(), doctor.getDisplayName(), views.stream().map(v -> row(ctx, date, v)).toList());
    }

    private static Row row(Ctx ctx, LocalDate date, AppointmentView view) {
        AppointmentStatus status = view.status();
        boolean open = status == AppointmentStatus.BOOKED || status == AppointmentStatus.CONFIRMED;
        boolean started = !ctx.now().isBefore(view.startsAt());
        boolean today = date.equals(ctx.today());
        return new Row(
                view.id(),
                ctx.formats().time(view.startsAt()),
                view.patientName(),
                view.patientPhone(),
                status.name(),
                view.source().name(),
                status == AppointmentStatus.CANCELLED,
                view.cancelledBy() == CancelledBy.PATIENT,
                today && (open || status == AppointmentStatus.NO_SHOW),
                started && (open || status == AppointmentStatus.ARRIVED),
                open && !started,
                open && !started);
    }
}
