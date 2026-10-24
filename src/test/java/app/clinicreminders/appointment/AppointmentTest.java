package app.clinicreminders.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;

import app.clinicreminders.appointment.AppointmentEvents.CancelledBy;
import org.junit.jupiter.api.Test;

class AppointmentTest {

    private static final Instant START = Instant.parse("2026-11-03T08:30:00Z");
    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");

    @Test
    void confirmingTwiceIsANoOp() {
        Appointment appointment = booked();

        assertThat(appointment.confirm(NOW)).isTrue();
        assertThat(appointment.confirm(NOW.plusSeconds(60))).isFalse();
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(appointment.getConfirmedAt()).isEqualTo(NOW);
    }

    @Test
    void movingAConfirmedAppointmentNeedsConfirmingAgain() {
        Appointment appointment = booked();
        appointment.confirm(NOW);

        appointment.reschedule(START.plusSeconds(86_400), START.plusSeconds(86_400 + 1800));

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.BOOKED);
        assertThat(appointment.getConfirmedAt()).isNull();
        assertThat(appointment.getStartsAt()).isEqualTo(START.plusSeconds(86_400));
    }

    @Test
    void arrivingKeepsWhenThePatientConfirmed() {
        Appointment appointment = booked();
        appointment.confirm(NOW);

        appointment.markArrived();

        assertThat(appointment.getConfirmedAt()).isEqualTo(NOW);
    }

    @Test
    void cancellingRecordsWhenAndByWhom() {
        Appointment appointment = booked();

        appointment.cancel(NOW, CancelledBy.PATIENT);

        assertThat(appointment.isActive()).isFalse();
        assertThat(appointment.getCancelledAt()).isEqualTo(NOW);
        assertThat(appointment.getCancelledBy()).isEqualTo(CancelledBy.PATIENT);
    }

    @Test
    void aCancelledAppointmentCanNotChangeAnyMore() {
        Appointment appointment = booked();
        appointment.cancel(NOW, CancelledBy.STAFF);

        assertThatIllegalStateException().isThrownBy(() -> appointment.confirm(NOW));
        assertThatIllegalStateException().isThrownBy(() -> appointment.reschedule(START, START.plusSeconds(60)));
        assertThatIllegalStateException().isThrownBy(() -> appointment.cancel(NOW, CancelledBy.STAFF));
        assertThatIllegalStateException().isThrownBy(appointment::markArrived);
        assertThatIllegalStateException().isThrownBy(() -> appointment.markNoShow(START.plusSeconds(60)));
    }

    @Test
    void noShowOnlyOnceItHasStartedAndArrivedCanCorrectIt() {
        Appointment appointment = booked();

        assertThatIllegalStateException().isThrownBy(() -> appointment.markNoShow(START.minusSeconds(1)));
        appointment.markNoShow(START.plusSeconds(600));
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.NO_SHOW);

        appointment.markArrived();
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.ARRIVED);
    }

    private static Appointment booked() {
        return new Appointment(1, 2, 3, START, START.plusSeconds(1800), AppointmentSource.MANUAL);
    }
}
