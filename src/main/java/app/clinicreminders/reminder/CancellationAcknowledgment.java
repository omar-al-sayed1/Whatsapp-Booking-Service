package app.clinicreminders.reminder;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentEvents.Cancelled;
import app.clinicreminders.appointment.AppointmentEvents.CancelledBy;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.whatsapp.WhatsAppMessenger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells the patient their cancellation went through (SPEC section 5.1.5). Runs only after the
 * cancellation committed, and outside any transaction so the HTTP call holds no connection.
 */
@Component
class CancellationAcknowledgment {

    private static final Logger log = LoggerFactory.getLogger(CancellationAcknowledgment.class);

    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final DoctorRepository doctors;
    private final ClinicRepository clinics;
    private final ReminderMessages messages;
    private final WhatsAppMessenger messenger;

    CancellationAcknowledgment(AppointmentRepository appointments, PatientRepository patients, DoctorRepository doctors,
                               ClinicRepository clinics, ReminderMessages messages, WhatsAppMessenger messenger) {
        this.appointments = appointments;
        this.patients = patients;
        this.doctors = doctors;
        this.clinics = clinics;
        this.messages = messages;
        this.messenger = messenger;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void onCancelled(Cancelled event) {
        if (event.by() != CancelledBy.PATIENT) {
            return;
        }
        long clinicId = event.clinicId();
        try {
            Appointment appointment = appointments.findByIdAndClinicId(event.appointmentId(), clinicId).orElseThrow();
            var patient = patients.findByIdAndClinicId(appointment.getPatientId(), clinicId).orElseThrow();
            var doctor = doctors.findByIdAndClinicId(appointment.getDoctorId(), clinicId).orElseThrow();
            var zone = clinics.findById(clinicId).orElseThrow().getTimezone();
            messenger.send(clinicId, messages.cancellationAcknowledgment(patient, doctor, appointment.getStartsAt(), zone),
                    appointment.getId());
        } catch (RuntimeException e) {
            log.error("Clinic {}: could not acknowledge the cancellation of appointment {}", clinicId,
                    event.appointmentId(), e);
        }
    }
}
