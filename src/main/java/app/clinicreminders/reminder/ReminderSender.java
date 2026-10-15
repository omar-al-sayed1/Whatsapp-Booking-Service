package app.clinicreminders.reminder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.whatsapp.OutboundMessage.TemplateMessage;
import app.clinicreminders.whatsapp.WhatsAppMessenger;
import app.clinicreminders.whatsapp.WhatsAppMessenger.SendOutcome;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends due reminders, one at a time: claim (committed as SENDING), check it should still go,
 * send through {@link WhatsAppMessenger} (outside any transaction), record the outcome. A reminder
 * that fails for any reason ends FAILED and is never claimed again: at most once.
 */
@Component
public class ReminderSender {

    private static final Logger log = LoggerFactory.getLogger(ReminderSender.class);

    private sealed interface Decision {
    }

    private record Skip(ReminderStatus status, String reason) implements Decision {
    }

    private record Send(long clinicId, long appointmentId, TemplateMessage message) implements Decision {
    }

    private final ReminderRepository reminders;
    private final AppointmentRepository appointments;
    private final ClinicRepository clinics;
    private final PatientRepository patients;
    private final DoctorRepository doctors;
    private final ReminderMessages messages;
    private final WhatsAppMessenger messenger;
    private final TransactionTemplate transaction;
    private final Clock clock;

    ReminderSender(ReminderRepository reminders, AppointmentRepository appointments, ClinicRepository clinics,
                   PatientRepository patients, DoctorRepository doctors, ReminderMessages messages,
                   WhatsAppMessenger messenger, TransactionTemplate transaction, Clock clock) {
        this.reminders = reminders;
        this.appointments = appointments;
        this.clinics = clinics;
        this.patients = patients;
        this.doctors = doctors;
        this.messages = messages;
        this.messenger = messenger;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * Handles due reminders until none is left or {@code max} were handled.
     *
     * @return how many were handled (sent, skipped or failed)
     */
    public int sendDue(int max) {
        int handled = 0;
        while (handled < max) {
            Optional<Long> claimed = reminders.claimNextDue(clock.instant());
            if (claimed.isEmpty()) {
                break;
            }
            handle(claimed.get());
            handled++;
        }
        return handled;
    }

    private void handle(long reminderId) {
        try {
            Decision decision = transaction.execute(status -> decide(reminderId));
            switch (decision) {
                case Skip skip -> {
                    log.info("Reminder {} not sent: {}", reminderId, skip.reason());
                    record(reminderId, skip.status(), null);
                }
                case Send send -> {
                    SendOutcome outcome = messenger.send(send.clinicId(), send.message(), send.appointmentId());
                    switch (outcome.result()) {
                        case SENT -> record(reminderId, ReminderStatus.SENT, outcome.waMessageId());
                        case FAILED -> record(reminderId, ReminderStatus.FAILED, null);
                        case OPTED_OUT -> record(reminderId, ReminderStatus.SKIPPED, null);
                    }
                }
                case null -> throw new IllegalStateException("No decision for reminder " + reminderId);
            }
        } catch (RuntimeException e) {
            log.error("Reminder {} could not be processed; marking it FAILED", reminderId, e);
            record(reminderId, ReminderStatus.FAILED, null);
        }
    }

    private Decision decide(long reminderId) {
        Reminder reminder = reminders.findClaimed(reminderId)
                .orElseThrow(() -> new NotFoundException("Claimed reminder", reminderId));
        long clinicId = reminder.getClinicId();
        Appointment appointment = appointments.findByIdAndClinicId(reminder.getAppointmentId(), clinicId)
                .orElseThrow(() -> new NotFoundException("Appointment", reminder.getAppointmentId()));
        if (!appointment.isActive()) {
            return new Skip(ReminderStatus.CANCELLED, "the appointment was cancelled");
        }
        Instant now = clock.instant();
        if (!appointment.getStartsAt().isAfter(now)) {
            return new Skip(ReminderStatus.SKIPPED, "the appointment has already started");
        }
        Clinic clinic = clinics.findById(clinicId).orElseThrow(() -> new NotFoundException("Clinic", clinicId));
        if (!switchedOn(clinic, reminder.getKind())) {
            return new Skip(ReminderStatus.SKIPPED, "the clinic switched " + reminder.getKind() + " reminders off");
        }
        ZoneId zone = clinic.getTimezone();
        LocalDateTime localNow = LocalDateTime.ofInstant(now, zone);
        if (new QuietHours(clinic.getQuietHoursStart(), clinic.getQuietHoursEnd()).contains(localNow.toLocalTime())) {
            return new Skip(ReminderStatus.SKIPPED, "running late and it is now quiet hours");
        }
        if (reminder.getKind() == ReminderKind.H24) {
            var dayBefore = LocalDateTime.ofInstant(appointment.getStartsAt(), zone).toLocalDate().minusDays(1);
            if (!localNow.toLocalDate().equals(dayBefore)) {
                return new Skip(ReminderStatus.SKIPPED, "too late for a message that says \"tomorrow\"");
            }
        }
        if (appointment.getPatientId() == null) {
            return new Skip(ReminderStatus.SKIPPED, "the patient was deleted");
        }
        Patient patient = patients.findByIdAndClinicId(appointment.getPatientId(), clinicId)
                .orElseThrow(() -> new NotFoundException("Patient", appointment.getPatientId()));
        if (patient.isOptedOut()) {
            return new Skip(ReminderStatus.SKIPPED, "the patient opted out");
        }
        Doctor doctor = doctors.findByIdAndClinicId(appointment.getDoctorId(), clinicId)
                .orElseThrow(() -> new NotFoundException("Doctor", appointment.getDoctorId()));
        return new Send(clinicId, appointment.getId(),
                messages.reminder(reminder.getKind(), patient, doctor, appointment.getStartsAt(), zone));
    }

    private void record(long reminderId, ReminderStatus outcome, @Nullable String waMessageId) {
        transaction.executeWithoutResult(status -> reminders.findClaimed(reminderId).ifPresent(reminder -> {
            if (outcome == ReminderStatus.SENT) {
                reminder.markSent(waMessageId, clock.instant());
            } else {
                reminder.finish(outcome);
            }
        }));
    }

    private static boolean switchedOn(Clinic clinic, ReminderKind kind) {
        return switch (kind) {
            case H24 -> clinic.isReminderH24Enabled();
            case H2 -> clinic.isReminderH2Enabled();
        };
    }
}
