package app.clinicreminders.reminder;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentEvents.CancelledDoctorAway;
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
 * Tells the patient the clinic cancelled their appointment because the doctor won't be there, with
 * a button to book another time (the booking flow takes the tap). A template, as the patient may
 * not have written in the last 24 hours. Like {@link CancellationAcknowledgment}: only after the
 * cancellation committed, and outside any transaction. An opted-out number gets nothing, and a
 * failed send shows under Needs attention, so the secretary calls instead.
 */
@Component
class DoctorAwayNotice {

    private static final Logger log = LoggerFactory.getLogger(DoctorAwayNotice.class);

    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final DoctorRepository doctors;
    private final ClinicRepository clinics;
    private final ReminderMessages messages;
    private final WhatsAppMessenger messenger;

    DoctorAwayNotice(AppointmentRepository appointments, PatientRepository patients, DoctorRepository doctors,
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
    void onCancelled(CancelledDoctorAway event) {
        long clinicId = event.clinicId();
        try {
            Appointment appointment = appointments.findByIdAndClinicId(event.appointmentId(), clinicId).orElseThrow();
            if (appointment.getPatientId() == null) {
                return; // the patient was deleted
            }
            var patient = patients.findByIdAndClinicId(appointment.getPatientId(), clinicId).orElseThrow();
            var doctor = doctors.findByIdAndClinicId(appointment.getDoctorId(), clinicId).orElseThrow();
            var zone = clinics.findById(clinicId).orElseThrow().getTimezone();
            messenger.send(clinicId, messages.doctorAway(patient, doctor, appointment.getStartsAt(), zone), appointment.getId());
        } catch (RuntimeException e) {
            log.error("Clinic {}: could not tell the patient of appointment {} that the doctor is away", clinicId,
                    event.appointmentId(), e);
        }
    }
}
