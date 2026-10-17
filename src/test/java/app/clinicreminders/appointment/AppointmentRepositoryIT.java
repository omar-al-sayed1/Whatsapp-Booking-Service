package app.clinicreminders.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    @Test
    void insertUnlessTakenBooksAFreeStartAndSkipsATakenOne() {
        Optional<Long> booked = insert(patient, FROM);
        Patient other = data.patient(clinic);

        assertThat(booked).isPresent();
        assertThat(insert(other, FROM)).isEmpty();
        assertThat(insert(other, FROM.plus(HALF_HOUR))).isPresent();
        Appointment saved = appointments.findByIdAndClinicId(booked.get(), clinic.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(AppointmentStatus.BOOKED);
        assertThat(saved.getSource()).isEqualTo(AppointmentSource.WHATSAPP);
        assertThat(saved.getEndsAt()).isEqualTo(FROM.plus(HALF_HOUR));
    }

    @Test
    void insertUnlessTakenIgnoresCancelledAppointmentsAndOtherDoctors() {
        data.cancelledAppointment(doctor, patient, FROM, HALF_HOUR);
        data.appointment(data.doctor(clinic, 30), patient, FROM, HALF_HOUR);

        assertThat(insert(patient, FROM)).isPresent();
    }

    @Test
    void findNextOpenSkipsPastCancelledAndArrivedAppointments() {
        Instant now = FROM;
        data.appointment(doctor, patient, now.minus(HALF_HOUR), HALF_HOUR);
        data.cancelledAppointment(doctor, patient, now.plus(HALF_HOUR), HALF_HOUR);
        Appointment arrived = data.appointment(doctor, patient, now.plus(Duration.ofHours(1)), HALF_HOUR);
        arrived.markArrived();
        appointments.save(arrived);
        Appointment later = data.appointment(doctor, patient, now.plus(Duration.ofDays(2)), HALF_HOUR);
        Appointment next = data.appointment(doctor, patient, now.plus(Duration.ofDays(1)), HALF_HOUR);
        next.confirm();
        appointments.save(next);

        assertThat(appointments.findNextOpen(clinic.getId(), patient.getId(), now)).get()
                .extracting(Appointment::getId).isEqualTo(next.getId());
        assertThat(appointments.findNextOpen(clinic.getId(), patient.getId(), next.getStartsAt())).get()
                .extracting(Appointment::getId).isEqualTo(later.getId());
        assertThat(appointments.findNextOpen(clinic.getId(), data.patient(clinic).getId(), now)).isEmpty();
    }

    @Test
    void findNextOpenStaysInItsClinic() {
        data.appointment(doctor, patient, FROM, HALF_HOUR);

        assertThat(appointments.findNextOpen(data.clinic("B").getId(), patient.getId(), FROM.minus(HALF_HOUR))).isEmpty();
    }

    private Optional<Long> insert(Patient who, Instant start) {
        return appointments.insertUnlessTaken(clinic.getId(), doctor.getId(), who.getId(), start, start.plus(HALF_HOUR),
                AppointmentSource.WHATSAPP.name());
    }

    @Test
    void findUpcomingOfGivesOpenFutureAppointmentsOfThosePatientsInThisClinic() {
        Patient other = data.patient(clinic);
        Appointment soon = data.appointment(doctor, patient, FROM.plus(Duration.ofDays(1)), HALF_HOUR);
        Appointment later = data.appointment(doctor, patient, FROM.plus(Duration.ofDays(2)), HALF_HOUR);
        data.appointment(doctor, patient, FROM.minus(Duration.ofDays(1)), HALF_HOUR);
        data.cancelledAppointment(doctor, other, FROM.plus(Duration.ofDays(1)).plus(HALF_HOUR), HALF_HOUR);
        Appointment theirs = data.appointment(doctor, other, FROM.plus(Duration.ofDays(3)), HALF_HOUR);

        assertThat(appointments.findUpcomingOf(clinic.getId(), List.of(patient.getId()), FROM))
                .extracting(Appointment::getId).containsExactly(soon.getId(), later.getId());
        assertThat(appointments.findUpcomingOf(clinic.getId(), List.of(other.getId()), FROM))
                .extracting(Appointment::getId).containsExactly(theirs.getId());
        assertThat(appointments.findUpcomingOf(data.clinic("B").getId(), List.of(patient.getId()), FROM)).isEmpty();
    }

    @Test
    void countUpcomingOfDoctorCountsTheirOpenFutureAppointmentsInThisClinic() {
        data.appointment(doctor, patient, FROM.plus(Duration.ofDays(1)), HALF_HOUR);
        data.appointment(doctor, patient, FROM.plus(Duration.ofDays(2)), HALF_HOUR);
        data.appointment(doctor, patient, FROM.minus(Duration.ofDays(1)), HALF_HOUR);
        data.cancelledAppointment(doctor, patient, FROM.plus(Duration.ofDays(3)), HALF_HOUR);
        data.appointment(data.doctor(clinic, 30), patient, FROM.plus(Duration.ofDays(1)), HALF_HOUR);

        assertThat(appointments.countUpcomingOfDoctor(clinic.getId(), doctor.getId(), FROM)).isEqualTo(2);
        assertThat(appointments.countUpcomingOfDoctor(data.clinic("B").getId(), doctor.getId(), FROM)).isZero();
    }
}
