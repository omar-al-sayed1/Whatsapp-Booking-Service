package app.clinicreminders;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicInteger;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentSource;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.scheduling.ScheduleException;
import app.clinicreminders.scheduling.ScheduleExceptionRepository;
import app.clinicreminders.scheduling.ScheduleRule;
import app.clinicreminders.scheduling.ScheduleRuleRepository;
import app.clinicreminders.scheduling.TimeWindow;
import app.clinicreminders.whatsapp.MessageRate;
import app.clinicreminders.whatsapp.MessageRateRepository;
import app.clinicreminders.whatsapp.TokenCipher;

/** Saves test fixtures through the real repositories. */
public class TestData {

    private final AtomicInteger phoneSequence = new AtomicInteger();
    private final ClinicRepository clinics;
    private final DoctorRepository doctors;
    private final ScheduleRuleRepository rules;
    private final ScheduleExceptionRepository exceptions;
    private final PatientRepository patients;
    private final AppointmentRepository appointments;
    private final MessageRateRepository rates;
    private final TokenCipher tokenCipher;

    TestData(ClinicRepository clinics, DoctorRepository doctors, ScheduleRuleRepository rules,
             ScheduleExceptionRepository exceptions, PatientRepository patients, AppointmentRepository appointments,
             MessageRateRepository rates, TokenCipher tokenCipher) {
        this.clinics = clinics;
        this.doctors = doctors;
        this.rules = rules;
        this.exceptions = exceptions;
        this.patients = patients;
        this.appointments = appointments;
        this.rates = rates;
        this.tokenCipher = tokenCipher;
    }

    public Clinic clinic(String name) {
        return clinics.save(new Clinic(name, Clinic.DEFAULT_ZONE, Language.AR));
    }

    /** A clinic connected to WhatsApp, with the token "token-{phoneNumberId}" encrypted for it. */
    public Clinic connectedClinic(String name, String phoneNumberId) {
        Clinic clinic = clinic(name);
        clinic.connectWhatsApp(phoneNumberId, "waba-" + phoneNumberId,
                tokenCipher.encrypt(clinic.getId(), "token-" + phoneNumberId));
        return clinics.save(clinic);
    }

    public MessageRate rate(String market, String category, String usd, LocalDate validFrom) {
        return rates.save(new MessageRate(market, category, new BigDecimal(usd), validFrom));
    }

    public Doctor doctor(Clinic clinic, int slotMinutes) {
        return doctors.save(new Doctor(clinic.getId(), "Dr. Test", slotMinutes));
    }

    public ScheduleRule weekly(Doctor doctor, DayOfWeek day, String start, String end) {
        return rules.save(new ScheduleRule(doctor.getClinicId(), doctor.getId(), day, window(start, end)));
    }

    public ScheduleException closedOn(Doctor doctor, LocalDate date) {
        return exceptions.save(ScheduleException.closed(doctor.getClinicId(), doctor.getId(), date));
    }

    public ScheduleException hoursOn(Doctor doctor, LocalDate date, String start, String end) {
        return exceptions.save(ScheduleException.withHours(doctor.getClinicId(), doctor.getId(), date, window(start, end)));
    }

    public Patient patient(Clinic clinic) {
        String phone = "+96170%06d".formatted(phoneSequence.incrementAndGet());
        return patients.save(new Patient(clinic.getId(), "Test Patient", phone, Language.AR));
    }

    public Patient patient(Clinic clinic, String phoneE164) {
        return patients.save(new Patient(clinic.getId(), "Test Patient", phoneE164, Language.AR));
    }

    public Appointment appointment(Doctor doctor, Patient patient, Instant start, Duration length) {
        return appointments.save(new Appointment(doctor.getClinicId(), doctor.getId(), patient.getId(), start,
                start.plus(length), AppointmentSource.MANUAL));
    }

    public Appointment cancelledAppointment(Doctor doctor, Patient patient, Instant start, Duration length) {
        Appointment appointment = new Appointment(doctor.getClinicId(), doctor.getId(), patient.getId(), start,
                start.plus(length), AppointmentSource.WHATSAPP);
        appointment.cancel(start.minus(Duration.ofDays(1)));
        return appointments.save(appointment);
    }

    private static TimeWindow window(String start, String end) {
        return new TimeWindow(LocalTime.parse(start), LocalTime.parse(end));
    }
}
