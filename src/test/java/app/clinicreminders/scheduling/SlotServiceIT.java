package app.clinicreminders.scheduling;

import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.patient.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The slot engine fed from the database; the engine's own rules are covered by SlotEngineTest. */
class SlotServiceIT extends IntegrationTest {

    private static final ZoneId BEIRUT = ZoneId.of("Asia/Beirut");
    private static final LocalDate MON = LocalDate.of(2026, 11, 2);
    private static final LocalDate TUE = MON.plusDays(1);

    @Autowired
    SlotService slots;

    @Autowired
    DoctorRepository doctors;

    private Clinic clinic;
    private Doctor doctor;
    private Patient patient;

    @BeforeEach
    void clinicWithDoctor() {
        clinic = data.clinic("A");
        doctor = data.doctor(clinic, 30);
        patient = data.patient(clinic);
        data.weekly(doctor, MONDAY, "09:00", "11:00");
        data.weekly(doctor, TUESDAY, "09:00", "11:00");
    }

    @Test
    void combinesHoursExceptionsAndActiveAppointments() {
        data.closedOn(doctor, TUE);
        data.appointment(doctor, patient, local(MON, "09:30"), Duration.ofMinutes(30));
        data.cancelledAppointment(doctor, patient, local(MON, "10:00"), Duration.ofMinutes(30));

        assertThat(dateTimes(slots.freeSlots(clinic.getId(), doctor.getId(), MON, TUE)))
                .containsExactly("2026-11-02 09:00", "2026-11-02 10:00", "2026-11-02 10:30");
    }

    @Test
    void usesTheInjectedClockForNow() {
        clock.setInstant(local(MON, "09:45"));

        assertThat(dateTimes(slots.freeSlots(clinic.getId(), doctor.getId(), MON, MON)))
                .containsExactly("2026-11-02 10:00", "2026-11-02 10:30");
    }

    @Test
    void appointmentsBookedUnderAnOldSlotLengthStillBlock() {
        doctor.changeSlotMinutes(15);
        doctor = doctors.save(doctor);
        data.appointment(doctor, patient, local(MON, "09:15"), Duration.ofMinutes(15));
        doctor.changeSlotMinutes(20);
        doctor = doctors.save(doctor);

        assertThat(dateTimes(slots.freeSlots(clinic.getId(), doctor.getId(), MON, MON)))
                .containsExactly("2026-11-02 09:40", "2026-11-02 10:00", "2026-11-02 10:20", "2026-11-02 10:40");
    }

    @Test
    void appointmentStoredBeforeTheOctoberChangeBlocksTheRightSlotAfterIt() {
        // Today is 14 October 2026 (UTC+3); the appointment is on Monday 26 October (UTC+2).
        LocalDate mondayAfterChange = LocalDate.of(2026, 10, 26);
        data.appointment(doctor, patient, Instant.parse("2026-10-26T08:00:00Z"), Duration.ofMinutes(30));

        assertThat(dateTimes(slots.freeSlots(clinic.getId(), doctor.getId(), mondayAfterChange, mondayAfterChange)))
                .containsExactly("2026-10-26 09:00", "2026-10-26 09:30", "2026-10-26 10:30");
    }

    @Test
    void inactiveDoctorHasNoSlots() {
        doctor.deactivate();
        doctors.save(doctor);

        assertThat(slots.freeSlots(clinic.getId(), doctor.getId(), MON, TUE)).isEmpty();
    }

    @Test
    void anotherClinicsDoctorIsNotFound() {
        Clinic otherClinic = data.clinic("B");

        assertThatThrownBy(() -> slots.freeSlots(otherClinic.getId(), doctor.getId(), MON, TUE))
                .isInstanceOf(NotFoundException.class);
    }

    private static Instant local(LocalDate date, String time) {
        return date.atTime(LocalTime.parse(time)).atZone(BEIRUT).toInstant();
    }

    private static List<String> dateTimes(List<Slot> slots) {
        var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        return slots.stream().map(s -> s.start().atZone(BEIRUT).format(formatter)).toList();
    }
}
