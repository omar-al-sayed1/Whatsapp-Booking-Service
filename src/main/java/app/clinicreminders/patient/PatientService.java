package app.clinicreminders.patient;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentEvents.CancelledBy;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentService;
import app.clinicreminders.booking.ConversationRepository;
import app.clinicreminders.common.Language;
import app.clinicreminders.common.NotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

    /**
     * The patient with this number and name (any case), or a new one: a family member when others
     * already have the number. An existing patient keeps their language; a new one on a number that
     * opted out is opted out too.
     */
    public Patient findOrCreate(long clinicId, String phoneE164, String name, Language language) {
        List<Patient> household = household(clinicId, phoneE164);
        return household.stream()
                .filter(patient -> patient.hasName(name))
                .findFirst()
                .orElseGet(() -> {
                    Patient patient = new Patient(clinicId, name, phoneE164, language);
                    if (household.stream().anyMatch(Patient::isOptedOut)) {
                        patient.optOut();
                    }
                    return patients.save(patient);
                });
    }

    /** Everyone on this number (a parent and their children), first added first. */
    @Transactional(readOnly = true)
    public List<Patient> household(long clinicId, String phoneE164) {
        return patients.findByClinicIdAndPhoneE164OrderById(clinicId, phoneE164);
    }

    /** Everyone on any of these numbers, by number and then by name: who shares a number with whom. */
    @Transactional(readOnly = true)
    public Map<String, List<Patient>> households(long clinicId, Collection<String> phones) {
        return phones.isEmpty() ? Map.of() : patients.findByClinicIdAndPhoneE164InOrderByName(clinicId, phones).stream()
                .collect(Collectors.groupingBy(Patient::getPhoneE164));
    }

    /** One page of a patient search, and how many match in all. */
    public record Found(List<Patient> patients, long total) {
    }

    /**
     * By name, or by phone when the query looks like a number (any digits typed are matched), one
     * page at a time (from 0). With a doctor, only the patients with an appointment with them.
     */
    @Transactional(readOnly = true)
    public Found search(long clinicId, @Nullable Long doctorId, String query, int page, int size) {
        String name = query.strip();
        String digits = searchDigits(name);
        Pageable pageable = PageRequest.of(Math.max(page, 0), size);
        return doctorId == null
                ? new Found(patients.search(clinicId, name, digits, pageable), patients.countSearch(clinicId, name, digits))
                : new Found(patients.searchOfDoctor(clinicId, doctorId, name, digits, pageable),
                        patients.countSearchOfDoctor(clinicId, doctorId, name, digits));
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
    static String searchDigits(String query) {
        String digits = query.replaceAll("\\D", "");
        if (digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return digits.length() < 3 ? "" : digits;
    }

    /**
     * Deletes a patient on request (SPEC section 5.3): cancels their future appointments, removes
     * their WhatsApp conversation (unless family members still use the number) and deletes the
     * patient. Past appointments stay for the monthly report with no patient attached.
     * {@code message_log} holds no phone numbers or message text, so nothing in it identifies them
     * once the link to the patient is gone.
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
        if (household(clinicId, patient.getPhoneE164()).size() == 1) {
            conversations.deleteByClinicIdAndPatientPhone(clinicId, patient.getPhoneE164());
        }
        patients.delete(patient);
    }
}
