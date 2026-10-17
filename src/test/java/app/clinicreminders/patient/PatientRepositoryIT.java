package app.clinicreminders.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Duration;
import java.time.Instant;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.scheduling.Doctor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The booking form's patient picker: patients booked at least once, latest first. */
class PatientRepositoryIT extends IntegrationTest {

    private static final Instant MONDAY = Instant.parse("2026-10-19T07:00:00Z");
    private static final Duration HALF_HOUR = Duration.ofMinutes(30);

    @Autowired
    PatientRepository patients;

    private Clinic clinic;
    private Doctor doctor;

    @BeforeEach
    void clinicWithDoctor() {
        clinic = data.clinic("A");
        doctor = data.doctor(clinic, 30);
    }

    @Test
    void onlyPatientsWithAnAppointmentLatestFirst() {
        Patient rana = data.patient(clinic, "Rana Haddad", "+96170123456", Language.AR);
        Patient rania = data.patient(clinic, "Rania Saab", "+96171000001", Language.EN);
        data.patient(clinic, "Ranim Never Booked", "+96171000002", Language.AR);
        data.appointment(doctor, rana, MONDAY, HALF_HOUR);
        data.appointment(doctor, rana, MONDAY.plus(Duration.ofDays(7)), HALF_HOUR);
        data.cancelledAppointment(doctor, rania, MONDAY.plus(Duration.ofDays(14)), HALF_HOUR);

        assertThat(patients.searchBooked(clinic.getId(), "ran", ""))
                .extracting(BookedPatient::name, BookedPatient::lastAppointment)
                .containsExactly(
                        tuple("Rania Saab", MONDAY.plus(Duration.ofDays(14))),
                        tuple("Rana Haddad", MONDAY.plus(Duration.ofDays(7))));
    }

    @Test
    void matchesByPhoneDigitsAndCarriesLanguageAndOptOut() {
        Patient karim = data.patient(clinic, "Karim", "+96171555666", Language.EN);
        karim.optOut();
        patients.save(karim);
        data.appointment(doctor, karim, MONDAY, HALF_HOUR);

        assertThat(patients.searchBooked(clinic.getId(), "71 555", "71555")).singleElement().satisfies(found -> {
            assertThat(found.phoneE164()).isEqualTo("+96171555666");
            assertThat(found.language()).isEqualTo(Language.EN);
            assertThat(found.optedOut()).isTrue();
        });
        assertThat(patients.searchBooked(clinic.getId(), "nobody", "")).isEmpty();
    }

    @Test
    void anEmptyQueryGivesTheEightMostRecent() {
        for (int i = 0; i < 10; i++) {
            Patient patient = data.patient(clinic, "Patient " + i, "+9617000010" + i, Language.AR);
            data.appointment(doctor, patient, MONDAY.plus(Duration.ofHours(i)), HALF_HOUR);
        }

        assertThat(patients.searchBooked(clinic.getId(), "", "")).hasSize(8)
                .first().extracting(BookedPatient::name).isEqualTo("Patient 9");
    }

    @Test
    void anotherClinicsPatientsAreNeverOffered() {
        Clinic other = data.clinic("B");
        Patient theirs = data.patient(other, "Rana Elsewhere", "+96170999999", Language.AR);
        data.appointment(data.doctor(other, 30), theirs, MONDAY, HALF_HOUR);

        assertThat(patients.searchBooked(clinic.getId(), "rana", "")).isEmpty();
    }
}
