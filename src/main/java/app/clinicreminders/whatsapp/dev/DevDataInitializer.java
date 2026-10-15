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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dev profile only: makes sure there is a clinic connected to the fake WhatsApp number, with a
 * doctor and a patient, so the simulator works on an empty database. M8's demo seed replaces this.
 */
@Component
@Profile("dev")
class DevDataInitializer implements ApplicationRunner {

    static final String DEV_PHONE_NUMBER_ID = "dev-phone-number-id";

    private static final Logger log = LoggerFactory.getLogger(DevDataInitializer.class);

    private final ClinicRepository clinics;
    private final DoctorRepository doctors;
    private final ScheduleRuleRepository rules;
    private final PatientRepository patients;

    DevDataInitializer(ClinicRepository clinics, DoctorRepository doctors, ScheduleRuleRepository rules,
                       PatientRepository patients) {
        this.clinics = clinics;
        this.doctors = doctors;
        this.rules = rules;
        this.patients = patients;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (clinics.findByWaPhoneNumberId(DEV_PHONE_NUMBER_ID).isPresent()) {
            return;
        }
        Clinic clinic = new Clinic("Dev Clinic", Clinic.DEFAULT_ZONE, Language.AR);
        clinic.connectWhatsApp(DEV_PHONE_NUMBER_ID, "dev-waba", null);
        clinic = clinics.save(clinic);
        Doctor doctor = doctors.save(new Doctor(clinic.getId(), "Dr. Dev", 30));
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day != DayOfWeek.SUNDAY) {
                rules.save(new ScheduleRule(clinic.getId(), doctor.getId(), day,
                        new TimeWindow(LocalTime.of(9, 0), LocalTime.of(13, 0))));
            }
        }
        patients.save(new Patient(clinic.getId(), "Dev Patient", "+96170000001", Language.AR));
        log.info("Created the dev clinic; simulate patients at /dev/whatsapp");
    }
}
