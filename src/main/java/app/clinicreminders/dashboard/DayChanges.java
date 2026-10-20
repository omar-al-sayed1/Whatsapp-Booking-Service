package app.clinicreminders.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentService;
import app.clinicreminders.appointment.AppointmentStatus;
import app.clinicreminders.scheduling.ScheduleSettings;
import app.clinicreminders.scheduling.TimeWindow;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closing a doctor's day, or giving it special hours, when patients are already booked then. The
 * appointments the new hours leave out are shown first; the owner, secretary or doctor then cancels
 * them (each patient gets a WhatsApp message to book another time) or keeps them.
 */
@Service
public class DayChanges {

    private final ScheduleSettings scheduleSettings;
    private final AppointmentRepository appointments;
    private final AppointmentService appointmentService;

    DayChanges(ScheduleSettings scheduleSettings, AppointmentRepository appointments,
               AppointmentService appointmentService) {
        this.scheduleSettings = scheduleSettings;
        this.appointments = appointments;
        this.appointmentService = appointmentService;
    }

    /**
     * The doctor's open (booked or confirmed) appointments still to come on {@code date} that don't
     * fit in {@code hours}: all of them when the day is closed ({@code hours} null). Earliest first.
     */
    @Transactional(readOnly = true)
    public List<Appointment> leftOut(long clinicId, long doctorId, LocalDate date, @Nullable TimeWindow hours,
                                     ZoneId zone, Instant now) {
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
        return appointments.findActiveOverlapping(clinicId, doctorId, from, to).stream()
                .filter(a -> a.getStatus() == AppointmentStatus.BOOKED || a.getStatus() == AppointmentStatus.CONFIRMED)
                .filter(a -> a.getStartsAt().isAfter(now) && !a.getStartsAt().isBefore(from))
                .filter(a -> hours == null || !fits(a, hours, date, zone))
                .toList();
    }

    /**
     * Saves the day off or special hours; with {@code cancel}, also cancels the appointments they
     * leave out, and their patients are told. Otherwise those appointments stay as booked.
     *
     * @return how many appointments were cancelled
     */
    @Transactional
    public int apply(long clinicId, long doctorId, LocalDate date, @Nullable TimeWindow hours, boolean cancel,
                     ZoneId zone, Instant now) {
        scheduleSettings.setDay(clinicId, doctorId, date, hours);
        if (!cancel) {
            return 0;
        }
        List<Appointment> leftOut = leftOut(clinicId, doctorId, date, hours, zone, now);
        leftOut.forEach(appointment -> appointmentService.cancelDoctorAway(clinicId, appointment.getId()));
        return leftOut.size();
    }

    /** Starts and ends within the hours, that day. */
    private static boolean fits(Appointment appointment, TimeWindow hours, LocalDate date, ZoneId zone) {
        LocalDateTime start = LocalDateTime.ofInstant(appointment.getStartsAt(), zone);
        LocalDateTime end = LocalDateTime.ofInstant(appointment.getEndsAt(), zone);
        return !start.isBefore(date.atTime(hours.start())) && !end.isAfter(date.atTime(hours.end()));
    }
}
