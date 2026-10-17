package app.clinicreminders.patient;

import java.time.Clock;
import java.util.List;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentEvents.CancelledBy;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentService;
import app.clinicreminders.booking.ConversationRepository;
import app.clinicreminders.common.Language;
import app.clinicreminders.common.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PatientService {

    private final PatientRepository patients;
    private final AppointmentRepository appointments;
    private final AppointmentService appointmentService;
    private final ConversationRepository conversations;
    private final Clock clock;

    PatientService(PatientRepository patients, AppointmentRepository appointments, AppointmentService appointmentService,
                   ConversationRepository conversations, Clock clock) {
        this.patients = patients;
        this.appointments = appointments;
        this.appointmentService = appointmentService;
        this.conversations = conversations;
        this.clock = clock;
    }

    /** The clinic's patient with this number, or a new one. An existing patient keeps their name and language. */
    public Patient findOrCreate(long clinicId, String phoneE164, String name, Language language) {
        return patients.findByClinicIdAndPhoneE164(clinicId, phoneE164)
                .orElseGet(() -> patients.save(new Patient(clinicId, name.strip(), phoneE164, language)));
    }

    /** By name, or by phone when the query looks like a number (any digits typed are matched). */
    @Transactional(readOnly = true)
    public List<Patient> search(long clinicId, String query) {
        String name = query.strip();
        return patients.search(clinicId, name, searchDigits(name));
    }

    /** As {@link #search}, among the patients with an appointment with that doctor. */
    @Transactional(readOnly = true)
    public List<Patient> searchOfDoctor(long clinicId, long doctorId, String query) {
        String name = query.strip();
        return patients.searchOfDoctor(clinicId, doctorId, name, searchDigits(name));
    }

    /**
     * For the booking form's patient picker: patients booked at least once, latest first, matched
     * like {@link #search}. An empty query gives the most recently booked.
     */
    @Transactional(readOnly = true)
    public List<BookedPatient> searchBooked(long clinicId, String query) {
        String name = query.strip();
        return patients.searchBooked(clinicId, name, searchDigits(name));
    }

    /** As {@link #searchBooked}, counting only appointments with that doctor. */
    @Transactional(readOnly = true)
    public List<BookedPatient> searchBookedWithDoctor(long clinicId, long doctorId, String query) {
        String name = query.strip();
        return patients.searchBookedWithDoctor(clinicId, doctorId, name, searchDigits(name));
    }

    /** The digits typed, without a leading 0 (03 123 456 is stored as +9613123456); none if fewer than 3. */
    private static String searchDigits(String query) {
        String digits = query.replaceAll("\\D", "");
        if (digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return digits.length() < 3 ? "" : digits;
    }

    /**
     * Deletes a patient on request (SPEC section 5.3): cancels their future appointments, removes
     * their WhatsApp conversation and deletes the patient. Past appointments stay for the monthly
     * report with no patient attached. {@code message_log} holds no phone numbers or message text,
     * so nothing in it identifies them once the link to the patient is gone.
     */
    public void delete(long clinicId, long patientId) {
        Patient patient = patients.findByIdAndClinicId(patientId, clinicId)
                .orElseThrow(() -> new NotFoundException("Patient", patientId));
        for (Appointment appointment : appointments.findByClinicIdAndPatientIdAndStartsAtAfter(clinicId, patientId,
                clock.instant())) {
            if (appointment.isActive()) {
                appointmentService.cancel(clinicId, appointment.getId(), CancelledBy.STAFF);
            }
        }
        conversations.deleteByClinicIdAndPatientPhone(clinicId, patient.getPhoneE164());
        patients.delete(patient);
    }
}
