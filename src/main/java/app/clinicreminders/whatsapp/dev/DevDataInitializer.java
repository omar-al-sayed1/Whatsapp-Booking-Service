package app.clinicreminders.whatsapp.dev;

import java.time.DayOfWeek;
import java.time.LocalTime;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.scheduling.ScheduleRule;
import app.clinicreminders.scheduling.ScheduleRuleRepository;
import app.clinicreminders.scheduling.TimeWindow;
import app.clinicreminders.staff.StaffRole;
import app.clinicreminders.staff.StaffUser;
import app.clinicreminders.staff.StaffUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dev profile only: makes sure there is a clinic connected to the fake WhatsApp number, with a
 * doctor, a patient and two logins ({@code owner} and {@code secretary}, password {@code dev}), plus
 * an {@code operator} login (password {@code dev}), so the dashboard, the operator pages and the
 * simulator work on an empty database. For sales demos use {@code --seed-demo} instead.
 */
@Component
@Profile("dev")
class DevDataInitializer implements ApplicationRunner {

    static final String DEV_PHONE_NUMBER_ID = "dev-phone-number-id";
    /** Development only; this profile never runs in production. */
    private static final String DEV_PASSWORD = "dev";

    private static final Logger log = LoggerFactory.getLogger(DevDataInitializer.class);

    private final ClinicRepository clinics;
    private final DoctorRepository doctors;
    private final ScheduleRuleRepository rules;
    private final PatientRepository patients;
    private final StaffUserRepository users;
    private final PasswordEncoder passwordEncoder;

    DevDataInitializer(ClinicRepository clinics, DoctorRepository doctors, ScheduleRuleRepository rules,
                       PatientRepository patients, StaffUserRepository users, PasswordEncoder passwordEncoder) {
        this.clinics = clinics;
        this.doctors = doctors;
        this.rules = rules;
        this.patients = patients;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Clinic clinic = clinics.findByWaPhoneNumberId(DEV_PHONE_NUMBER_ID).orElseGet(this::createClinic);
        ensureUser(clinic, "owner", StaffRole.OWNER);
        ensureUser(clinic, "secretary", StaffRole.SECRETARY);
        if (users.findByUsernameIgnoreCase("operator").isEmpty()) {
            users.save(StaffUser.operator("operator", passwordEncoder.encode(DEV_PASSWORD), Language.EN));
            log.info("Created dev login 'operator' (password '{}')", DEV_PASSWORD);
        }
    }

    private Clinic createClinic() {
        Clinic clinic = new Clinic("Dev Clinic", Clinic.DEFAULT_ZONE, Language.AR);
        clinic.connectWhatsApp(DEV_PHONE_NUMBER_ID, "dev-waba", null);
        clinic.changeContactDetails("Main street, Zahle", "Monday to Saturday, 9:00 to 13:00");
        clinic = clinics.save(clinic);
        Doctor doctor = doctors.save(new Doctor(clinic.getId(), "Dr. Dev", clinic.getSlotMinutes()));
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day != DayOfWeek.SUNDAY) {
                rules.save(new ScheduleRule(clinic.getId(), doctor.getId(), day,
                        new TimeWindow(LocalTime.of(9, 0), LocalTime.of(13, 0))));
            }
        }
        patients.save(new Patient(clinic.getId(), "Dev Patient", "+96170000001", Language.AR));
        log.info("Created the dev clinic; simulate patients at /dev/whatsapp");
        return clinic;
    }

    private void ensureUser(Clinic clinic, String username, StaffRole role) {
        if (users.findByUsernameIgnoreCase(username).isEmpty()) {
            users.save(StaffUser.clinicStaff(clinic.getId(), username, passwordEncoder.encode(DEV_PASSWORD), role, Language.AR));
            log.info("Created dev login '{}' (password '{}')", username, DEV_PASSWORD);
        }
    }
}
