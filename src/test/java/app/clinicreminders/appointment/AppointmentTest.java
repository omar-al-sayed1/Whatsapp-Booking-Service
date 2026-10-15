package app.clinicreminders.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class AppointmentTest {

    private static final Instant START = Instant.parse("2026-11-03T08:30:00Z");
    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");

    @Test
    void confirmingTwiceIsANoOp() {
        Appointment appointment = booked();

        assertThat(appointment.confirm()).isTrue();
        assertThat(appointment.confirm()).isFalse();
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
    }

    @Test
    void movingAConfirmedAppointmentNeedsConfirmingAgain() {
        Appointment appointment = booked();
        appointment.confirm();

        appointment.reschedule(START.plusSeconds(86_400), START.plusSeconds(86_400 + 1800));

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.BOOKED);
        assertThat(appointment.getStartsAt()).isEqualTo(START.plusSeconds(86_400));
    }

    @Test
    void aCancelledAppointmentCanNotBeConfirmedMovedOrCancelledAgain() {
        Appointment appointment = booked();
        appointment.cancel(NOW);

        assertThat(appointment.isActive()).isFalse();
        assertThat(appointment.getCancelledAt()).isEqualTo(NOW);
        assertThatIllegalStateException().isThrownBy(appointment::confirm);
        assertThatIllegalStateException().isThrownBy(() -> appointment.reschedule(START, START.plusSeconds(60)));
        assertThatIllegalStateException().isThrownBy(() -> appointment.cancel(NOW));
    }

    private static Appointment booked() {
        return new Appointment(1, 2, 3, START, START.plusSeconds(1800), AppointmentSource.MANUAL);
    }
}
