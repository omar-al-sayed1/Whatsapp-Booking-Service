package app.clinicreminders.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.appointment.AppointmentEvents.CancelledBy;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.reminder.Reminder;
import app.clinicreminders.reminder.ReminderKind;
import app.clinicreminders.reminder.ReminderRepository;
import app.clinicreminders.reminder.ReminderStatus;
import app.clinicreminders.scheduling.Doctor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Booking, moving and cancelling, and the reminder rows that follow (SPEC section 5.1). */
class AppointmentServiceIT extends IntegrationTest {

    /** Tuesday 3 November 2026, 10:30 in Beirut. "Now" is Wednesday 14 October. */
    private static final Instant TUE_1030 = Instant.parse("2026-11-03T08:30:00Z");

    @Autowired
    AppointmentService appointments;

    @Autowired
    AppointmentRepository appointmentRepository;

    @Autowired
    ReminderRepository reminders;

    @Autowired
    ClinicRepository clinics;

    private Clinic clinic;
    private Doctor doctor;
    private Patient patient;

    @BeforeEach
    void clinicWithDoctorAndPatient() {
        clinic = data.connectedClinic("A", "pn-a");
        doctor = data.doctor(clinic, 30);
        patient = data.patient(clinic, "+96170123456");
    }

    @Test
    void bookingPlansBothReminders() {
        Appointment appointment = book(TUE_1030);

        assertThat(appointment.getEndsAt()).isEqualTo(TUE_1030.plus(Duration.ofMinutes(30)));
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.BOOKED);
        assertThat(remindersOf(appointment)).extracting(Reminder::getKind, Reminder::getDueAt, Reminder::getStatus)
                .containsExactly(
                        tuple(ReminderKind.H24, Instant.parse("2026-11-02T08:30:00Z"), ReminderStatus.PENDING),
                        tuple(ReminderKind.H2, Instant.parse("2026-11-03T06:30:00Z"), ReminderStatus.PENDING));
    }

    @Test
    void bookingLessThanADayAheadSkipsTheH24() {
        clock.setInstant(Instant.parse("2026-11-02T10:00:00Z"));

        assertThat(remindersOf(book(TUE_1030))).extracting(Reminder::getStatus)
                .containsExactly(ReminderStatus.SKIPPED, ReminderStatus.PENDING);
    }

    @Test
    void kindsTheClinicSwitchedOffGetNoReminder() {
        clinic.configureReminders(false, true);
        clinics.save(clinic);

        assertThat(remindersOf(book(TUE_1030))).extracting(Reminder::getKind).containsExactly(ReminderKind.H2);
    }

    @Test
    void aStartTimeTheDoctorAlreadyHasIsRefused() {
        book(TUE_1030);
        Patient other = data.patient(clinic, "+96170999999");

        assertThatThrownBy(() -> appointments.book(clinic.getId(), doctor.getId(), other.getId(), TUE_1030,
                AppointmentSource.WHATSAPP)).isInstanceOf(SlotTakenException.class);
        assertThat(count("appointment")).isEqualTo(1);
        assertThat(count("reminder")).isEqualTo(2);
    }

    @Test
    void aCancelledSlotCanBeBookedAgain() {
        Appointment first = book(TUE_1030);
        appointments.cancel(clinic.getId(), first.getId(), CancelledBy.STAFF);

        assertThat(book(TUE_1030).getStatus()).isEqualTo(AppointmentStatus.BOOKED);
    }

    @Test
    void movingReplacesThePendingRemindersAndNeedsConfirmingAgain() {
        Appointment appointment = book(TUE_1030);
        appointments.confirm(clinic.getId(), appointment.getId());

        Appointment moved = appointments.reschedule(clinic.getId(), appointment.getId(), Instant.parse("2026-11-04T09:00:00Z"));

        assertThat(moved.getStatus()).isEqualTo(AppointmentStatus.BOOKED);
        assertThat(moved.getEndsAt()).isEqualTo(Instant.parse("2026-11-04T09:30:00Z"));
        assertThat(remindersOf(moved)).extracting(Reminder::getDueAt, Reminder::getStatus).containsExactly(
                tuple(Instant.parse("2026-11-02T08:30:00Z"), ReminderStatus.CANCELLED),
                tuple(Instant.parse("2026-11-03T06:30:00Z"), ReminderStatus.CANCELLED),
                tuple(Instant.parse("2026-11-03T09:00:00Z"), ReminderStatus.PENDING),
                tuple(Instant.parse("2026-11-04T07:00:00Z"), ReminderStatus.PENDING));
    }

    @Test
    void movingOntoATakenTimeChangesNothing() {
        book(TUE_1030);
        Appointment later = appointments.book(clinic.getId(), doctor.getId(), data.patient(clinic, "+96170999999").getId(),
                TUE_1030.plus(Duration.ofHours(1)), AppointmentSource.MANUAL);

        assertThatThrownBy(() -> appointments.reschedule(clinic.getId(), later.getId(), TUE_1030))
                .isInstanceOf(SlotTakenException.class);

        Appointment unchanged = appointmentRepository.findByIdAndClinicId(later.getId(), clinic.getId()).orElseThrow();
        assertThat(unchanged.getStartsAt()).isEqualTo(TUE_1030.plus(Duration.ofHours(1)));
        assertThat(remindersOf(later)).extracting(Reminder::getStatus)
                .containsExactly(ReminderStatus.PENDING, ReminderStatus.PENDING);
    }

    @Test
    void cancellingCancelsOnlyRemindersNotSentYetAndStaffCancellationsSendNothing() {
        Appointment appointment = book(TUE_1030);
        jdbc.update("update reminder set status = 'SENT' where kind = 'H24'");

        appointments.cancel(clinic.getId(), appointment.getId(), CancelledBy.STAFF);

        assertThat(appointmentRepository.findByIdAndClinicId(appointment.getId(), clinic.getId()).orElseThrow().getStatus())
                .isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(remindersOf(appointment)).extracting(Reminder::getStatus)
                .containsExactly(ReminderStatus.SENT, ReminderStatus.CANCELLED);
        assertThat(whatsApp.recent()).isEmpty();
    }

    @Test
    void confirmingTwiceIsHarmless() {
        Appointment appointment = book(TUE_1030);

        assertThat(appointments.confirm(clinic.getId(), appointment.getId())).isTrue();
        assertThat(appointments.confirm(clinic.getId(), appointment.getId())).isFalse();
    }

    @Test
    void anotherClinicsRecordsAreNotFound() {
        Clinic other = data.clinic("B");
        Doctor otherDoctor = data.doctor(other, 30);
        Patient otherPatient = data.patient(other, "+96170888888");
        Appointment appointment = book(TUE_1030);

        assertThatThrownBy(() -> appointments.book(clinic.getId(), otherDoctor.getId(), patient.getId(), TUE_1030,
                AppointmentSource.MANUAL)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> appointments.book(clinic.getId(), doctor.getId(), otherPatient.getId(), TUE_1030,
                AppointmentSource.MANUAL)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> appointments.cancel(other.getId(), appointment.getId(), CancelledBy.STAFF))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> appointments.confirm(other.getId(), appointment.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> appointments.reschedule(other.getId(), appointment.getId(), TUE_1030))
                .isInstanceOf(NotFoundException.class);
    }

    private Appointment book(Instant startsAt) {
        return appointments.book(clinic.getId(), doctor.getId(), patient.getId(), startsAt, AppointmentSource.MANUAL);
    }

    private List<Reminder> remindersOf(Appointment appointment) {
        return reminders.findByClinicIdAndAppointmentIdOrderByDueAt(clinic.getId(), appointment.getId());
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
