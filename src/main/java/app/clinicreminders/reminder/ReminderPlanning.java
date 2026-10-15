package app.clinicreminders.reminder;

import java.time.Clock;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentEvents.Cancelled;
import app.clinicreminders.appointment.AppointmentEvents.Scheduled;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.reminder.ReminderPlanner.Planned;
import app.clinicreminders.reminder.ReminderPlanner.Settings;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps reminder rows in step with appointments, inside the transaction that changed the
 * appointment: booking or moving replaces the pending reminders, cancelling cancels them.
 */
@Component
class ReminderPlanning {

    private final ReminderRepository reminders;
    private final AppointmentRepository appointments;
    private final ClinicRepository clinics;
    private final Clock clock;

    ReminderPlanning(ReminderRepository reminders, AppointmentRepository appointments, ClinicRepository clinics,
                     Clock clock) {
        this.reminders = reminders;
        this.appointments = appointments;
        this.clinics = clinics;
        this.clock = clock;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void onScheduled(Scheduled event) {
        reminders.cancelPending(event.clinicId(), event.appointmentId());
        Appointment appointment = appointments.findByIdAndClinicId(event.appointmentId(), event.clinicId())
                .orElseThrow(() -> new NotFoundException("Appointment", event.appointmentId()));
        Clinic clinic = clinics.findById(event.clinicId())
                .orElseThrow(() -> new NotFoundException("Clinic", event.clinicId()));
        Settings settings = new Settings(clinic.getTimezone(),
                new QuietHours(clinic.getQuietHoursStart(), clinic.getQuietHoursEnd()),
                clinic.isReminderH24Enabled(), clinic.isReminderH2Enabled());
        for (Planned planned : ReminderPlanner.plan(appointment.getStartsAt(), clock.instant(), settings)) {
            reminders.save(new Reminder(event.clinicId(), event.appointmentId(), planned.kind(), planned.dueAt(),
                    planned.status()));
        }
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void onCancelled(Cancelled event) {
        reminders.cancelPending(event.clinicId(), event.appointmentId());
    }
}
