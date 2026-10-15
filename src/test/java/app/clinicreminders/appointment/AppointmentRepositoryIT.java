package app.clinicreminders.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.scheduling.Doctor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AppointmentRepositoryIT extends IntegrationTest {

    private static final Duration HALF_HOUR = Duration.ofMinutes(30);
    private static final Instant FROM = Instant.parse("2026-11-02T07:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-02T09:00:00Z");

    @Autowired
    AppointmentRepository appointments;

    private Clinic clinic;
    private Doctor doctor;
    private Patient patient;

    @BeforeEach
    void clinicWithDoctor() {
        clinic = data.clinic("A");
        doctor = data.doctor(clinic, 30);
        patient = data.patient(clinic);
    }

    @Test
    void findsActiveAppointmentsOverlappingTheRangeInOrder() {
        Appointment later = data.appointment(doctor, patient, FROM.plus(Duration.ofMinutes(60)), HALF_HOUR);
        Appointment straddlingStart = data.appointment(doctor, patient, FROM.minus(Duration.ofMinutes(15)), HALF_HOUR);
        Appointment straddlingEnd = data.appointment(doctor, patient, TO.minus(Duration.ofMinutes(15)), HALF_HOUR);

        assertThat(appointments.findActiveOverlapping(clinic.getId(), doctor.getId(), FROM, TO))
                .extracting(Appointment::getId)
                .containsExactly(straddlingStart.getId(), later.getId(), straddlingEnd.getId());
    }

    @Test
    void ignoresCancelledAppointments() {
        data.cancelledAppointment(doctor, patient, FROM, HALF_HOUR);

        assertThat(appointments.findActiveOverlapping(clinic.getId(), doctor.getId(), FROM, TO)).isEmpty();
    }

    @Test
    void ignoresAppointmentsThatOnlyTouchTheRange() {
        data.appointment(doctor, patient, FROM.minus(HALF_HOUR), HALF_HOUR);
        data.appointment(doctor, patient, TO, HALF_HOUR);

        assertThat(appointments.findActiveOverlapping(clinic.getId(), doctor.getId(), FROM, TO)).isEmpty();
    }

    @Test
    void ignoresOtherDoctors() {
        data.appointment(data.doctor(clinic, 30), patient, FROM, HALF_HOUR);

        assertThat(appointments.findActiveOverlapping(clinic.getId(), doctor.getId(), FROM, TO)).isEmpty();
    }

    @Test
    void anAppointmentIsFoundByIdOnlyInItsClinic() {
        Appointment appointment = data.appointment(doctor, patient, FROM, HALF_HOUR);
        Clinic otherClinic = data.clinic("B");

        assertThat(appointments.findByIdAndClinicId(appointment.getId(), clinic.getId())).isPresent();
        assertThat(appointments.findByIdAndClinicId(appointment.getId(), otherClinic.getId())).isEmpty();
    }

    @Test
    void neverReturnsAnotherClinicsAppointments() {
        data.appointment(doctor, patient, FROM, HALF_HOUR);
        Clinic otherClinic = data.clinic("B");

        assertThat(appointments.findActiveOverlapping(otherClinic.getId(), doctor.getId(), FROM, TO)).isEmpty();
    }
}
