package app.clinicreminders.dashboard;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import app.clinicreminders.appointment.AppointmentEvents.CancelledBy;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentSource;
import app.clinicreminders.appointment.AppointmentStatus;
import app.clinicreminders.appointment.AppointmentView;
import app.clinicreminders.dashboard.ClinicContext.Ctx;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/** Builds the day's appointment list, per doctor, and its figures (the Today page and its 30-second refresh). */
@Component
class TodayList {

    /** One appointment as listed, with the actions that make sense for it right now. */
    record Row(Long id, String time, String patientName, String patientPhone, String status, String source,
               boolean cancelled, boolean cancelledByPatient, boolean past,
               boolean canArrive, boolean canNoShow, boolean canCancel, boolean canReschedule) {
    }

    /**
     * @param nowIndex where the "now" line goes: before the row at this index (the list's size puts
     *                 it last); -1 when the day isn't today
     */
    record DoctorDay(Long id, String name, List<Row> rows, int nowIndex) {

        public long active() {
            return rows.stream().filter(row -> !row.cancelled()).count();
        }
    }

    /**
     * The day at a glance. Confirmed share is of the appointments still to come or confirmed:
     * BOOKED and CONFIRMED.
     */
    record Stats(long total, long confirmed, int confirmedPercent, long arrived, long noShows, long cancelled,
                 long cancelledByPatient, long whatsApp) {
    }

    private final DoctorRepository doctors;
    private final AppointmentRepository appointments;

    TodayList(DoctorRepository doctors, AppointmentRepository appointments) {
        this.doctors = doctors;
        this.appointments = appointments;
    }

    /** A doctor's login sees their own column only, and the figures are theirs. */
    void fill(Ctx ctx, LocalDate date, Model model) {
        List<AppointmentView> views = appointments.findViews(ctx.clinicId(), ctx.startOf(date), ctx.startOf(date.plusDays(1)))
                .stream().filter(view -> ctx.sees(view.doctorId())).toList();
        Map<Long, List<AppointmentView>> byDoctor = views.stream().collect(Collectors.groupingBy(AppointmentView::doctorId));
        boolean today = date.equals(ctx.today());
        List<DoctorDay> days = doctors.findByClinicIdOrderByDisplayName(ctx.clinicId()).stream()
                .filter(doctor -> ctx.sees(doctor.getId()))
                .filter(doctor -> doctor.isActive() || byDoctor.containsKey(doctor.getId()))
                .map(doctor -> day(ctx, today, doctor, byDoctor.getOrDefault(doctor.getId(), List.of())))
                .toList();
        model.addAttribute("doctors", days);
        model.addAttribute("date", date);
        model.addAttribute("isToday", today);
        model.addAttribute("nowTime", ctx.formats().time(ctx.now()));
        model.addAttribute("stats", stats(views));
    }

    static Stats stats(List<AppointmentView> views) {
        long total = count(views, v -> v.status() != AppointmentStatus.CANCELLED);
        long confirmed = count(views, v -> v.status() == AppointmentStatus.CONFIRMED);
        long open = count(views, v -> v.status() == AppointmentStatus.BOOKED || v.status() == AppointmentStatus.CONFIRMED);
        return new Stats(total, confirmed, open == 0 ? 0 : (int) Math.round(100.0 * confirmed / open),
                count(views, v -> v.status() == AppointmentStatus.ARRIVED),
                count(views, v -> v.status() == AppointmentStatus.NO_SHOW),
                count(views, v -> v.status() == AppointmentStatus.CANCELLED),
                count(views, v -> v.status() == AppointmentStatus.CANCELLED && v.cancelledBy() == CancelledBy.PATIENT),
                count(views, v -> v.status() != AppointmentStatus.CANCELLED && v.source() == AppointmentSource.WHATSAPP));
    }

    private static long count(List<AppointmentView> views, Predicate<AppointmentView> test) {
        return views.stream().filter(test).count();
    }

    private static DoctorDay day(Ctx ctx, boolean today, Doctor doctor, List<AppointmentView> views) {
        List<Row> rows = views.stream().map(v -> row(ctx, today, v)).toList();
        int nowIndex = -1;
        if (today) {
            nowIndex = rows.size();
            for (int i = 0; i < views.size(); i++) {
                if (views.get(i).startsAt().isAfter(ctx.now())) {
                    nowIndex = i;
                    break;
                }
            }
        }
        return new DoctorDay(doctor.getId(), doctor.getDisplayName(), rows, nowIndex);
    }

    private static Row row(Ctx ctx, boolean today, AppointmentView view) {
        AppointmentStatus status = view.status();
        boolean open = status == AppointmentStatus.BOOKED || status == AppointmentStatus.CONFIRMED;
        boolean started = !ctx.now().isBefore(view.startsAt());
        return new Row(
                view.id(),
                ctx.formats().time(view.startsAt()),
                view.patientName(),
                view.patientPhone(),
                status.name(),
                view.source().name(),
                status == AppointmentStatus.CANCELLED,
                view.cancelledBy() == CancelledBy.PATIENT,
                view.endsAt().isBefore(ctx.now()),
                today && (open || status == AppointmentStatus.NO_SHOW),
                started && (open || status == AppointmentStatus.ARRIVED),
                open && !started,
                open && !started);
    }
}
