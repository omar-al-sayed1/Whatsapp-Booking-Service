package app.clinicreminders.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.clinic.Clinic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Every reminder query, each with its clinic-isolation case where it filters by clinic. */
class ReminderRepositoryIT extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-02T08:30:00Z");

    @Autowired
    ReminderRepository reminders;

    private Clinic clinicA;
    private Clinic clinicB;
    private Appointment appointmentA;

    @BeforeEach
    void appointmentWithoutReminders() {
        clinicA = data.clinic("A");
        clinicB = data.clinic("B");
        appointmentA = data.appointment(data.doctor(clinicA, 30), data.patient(clinicA),
                Instant.parse("2026-11-03T08:30:00Z"), Duration.ofMinutes(30));
    }

    @Test
    void anAppointmentsRemindersAreFoundOnlyInItsClinic() {
        reminder(ReminderKind.H24, NOW, ReminderStatus.PENDING);

        assertThat(reminders.findByClinicIdAndAppointmentIdOrderByDueAt(clinicA.getId(), appointmentA.getId())).hasSize(1);
        assertThat(reminders.findByClinicIdAndAppointmentIdOrderByDueAt(clinicB.getId(), appointmentA.getId())).isEmpty();
    }

    @Test
    void cancellingPendingRemindersIsScopedToTheClinicAndLeavesOthersAlone() {
        reminder(ReminderKind.H24, NOW, ReminderStatus.SENT);
        reminder(ReminderKind.H2, NOW.plusSeconds(3600), ReminderStatus.PENDING);

        assertThat(reminders.cancelPending(clinicB.getId(), appointmentA.getId())).isZero();
        assertThat(reminders.cancelPending(clinicA.getId(), appointmentA.getId())).isEqualTo(1);

        assertThat(reminders.findByClinicIdAndAppointmentIdOrderByDueAt(clinicA.getId(), appointmentA.getId()))
                .extracting(Reminder::getStatus).containsExactly(ReminderStatus.SENT, ReminderStatus.CANCELLED);
    }

    @Test
    void claimingTakesTheOldestDueReminderOnceAndMarksItSending() {
        Reminder later = reminder(ReminderKind.H2, NOW.minusSeconds(60), ReminderStatus.PENDING);
        Reminder older = reminder(ReminderKind.H24, NOW.minusSeconds(3600), ReminderStatus.PENDING);
        reminder(ReminderKind.H24, NOW.plusSeconds(1), ReminderStatus.SKIPPED);

        assertThat(reminders.claimNextDue(NOW)).contains(older.getId());
        assertThat(reminders.claimNextDue(NOW)).contains(later.getId());
        assertThat(reminders.claimNextDue(NOW)).isEmpty();

        Reminder claimed = reminders.findClaimed(older.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(ReminderStatus.SENDING);
        assertThat(claimed.getAttempts()).isEqualTo(1);
    }

    @Test
    void remindersNotYetDueAreNotClaimed() {
        reminder(ReminderKind.H24, NOW.plusSeconds(1), ReminderStatus.PENDING);

        assertThat(reminders.claimNextDue(NOW)).isEmpty();
    }

    @Test
    void onlyClaimedRemindersAreFoundAsClaimed() {
        Reminder pending = reminder(ReminderKind.H24, NOW, ReminderStatus.PENDING);

        assertThat(reminders.findClaimed(pending.getId())).isEmpty();
    }

    private Reminder reminder(ReminderKind kind, Instant dueAt, ReminderStatus status) {
        return reminders.save(new Reminder(clinicA.getId(), appointmentA.getId(), kind, dueAt, status));
    }
}
