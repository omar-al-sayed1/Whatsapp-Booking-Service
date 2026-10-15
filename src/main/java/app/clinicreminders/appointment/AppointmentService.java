package app.clinicreminders.appointment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import app.clinicreminders.appointment.AppointmentEvents.Cancelled;
import app.clinicreminders.appointment.AppointmentEvents.CancelledBy;
import app.clinicreminders.appointment.AppointmentEvents.Scheduled;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Books, moves, confirms and cancels appointments, always within one clinic. Reminders follow
 * through {@link AppointmentEvents}. Whether a time is a valid slot is the caller's decision
 * (the WhatsApp flow offers only free slots; the secretary may squeeze someone in); this
 * service only guarantees no two active appointments of a doctor start together.
 */
@Service
@Transactional
public class AppointmentService {

    private static final String DOUBLE_BOOKING_CONSTRAINT = "appointment_doctor_start_active_uq";

    private final AppointmentRepository appointments;
    private final DoctorRepository doctors;
    private final PatientRepository patients;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    AppointmentService(AppointmentRepository appointments, DoctorRepository doctors, PatientRepository patients,
                       ApplicationEventPublisher events, Clock clock) {
        this.appointments = appointments;
        this.doctors = doctors;
        this.patients = patients;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Books one slot of the doctor's current length.
     *
     * @throws SlotTakenException if the doctor already has an appointment starting then
     */
    public Appointment book(long clinicId, long doctorId, long patientId, Instant startsAt, AppointmentSource source) {
        Doctor doctor = doctors.findByIdAndClinicId(doctorId, clinicId)
                .orElseThrow(() -> new NotFoundException("Doctor", doctorId));
        patients.findByIdAndClinicId(patientId, clinicId).orElseThrow(() -> new NotFoundException("Patient", patientId));
        Appointment appointment = new Appointment(clinicId, doctorId, patientId, startsAt,
                startsAt.plus(doctor.slotLength()), source);
        appointment = saveGuarded(appointment);
        events.publishEvent(new Scheduled(clinicId, appointment.getId()));
        return appointment;
    }

    /** Moves the appointment, keeping its length. It goes back to BOOKED and gets new reminders. */
    public Appointment reschedule(long clinicId, long appointmentId, Instant newStartsAt) {
        Appointment appointment = find(clinicId, appointmentId);
        Duration length = Duration.between(appointment.getStartsAt(), appointment.getEndsAt());
        appointment.reschedule(newStartsAt, newStartsAt.plus(length));
        appointment = saveGuarded(appointment);
        events.publishEvent(new Scheduled(clinicId, appointmentId));
        return appointment;
    }

    /** @return false if it was already confirmed */
    public boolean confirm(long clinicId, long appointmentId) {
        return find(clinicId, appointmentId).confirm();
    }

    public void cancel(long clinicId, long appointmentId, CancelledBy by) {
        find(clinicId, appointmentId).cancel(clock.instant(), by);
        events.publishEvent(new Cancelled(clinicId, appointmentId, by));
    }

    public void markArrived(long clinicId, long appointmentId) {
        find(clinicId, appointmentId).markArrived();
    }

    public void markNoShow(long clinicId, long appointmentId) {
        find(clinicId, appointmentId).markNoShow(clock.instant());
    }

    private Appointment find(long clinicId, long appointmentId) {
        return appointments.findByIdAndClinicId(appointmentId, clinicId)
                .orElseThrow(() -> new NotFoundException("Appointment", appointmentId));
    }

    private Appointment saveGuarded(Appointment appointment) {
        try {
            return appointments.saveAndFlush(appointment);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMessage()).contains(DOUBLE_BOOKING_CONSTRAINT)) {
                throw new SlotTakenException(appointment.getDoctorId(), appointment.getStartsAt());
            }
            throw e;
        }
    }
}
