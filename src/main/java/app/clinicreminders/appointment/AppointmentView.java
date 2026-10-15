package app.clinicreminders.appointment;

import java.time.Instant;

import app.clinicreminders.appointment.AppointmentEvents.CancelledBy;
import org.jspecify.annotations.Nullable;

/**
 * An appointment with its patient's name and phone, for lists.
 *
 * @param patientName null when the patient was deleted on request
 */
public record AppointmentView(
        Long id,
        Long doctorId,
        Instant startsAt,
        Instant endsAt,
        AppointmentStatus status,
        AppointmentSource source,
        @Nullable CancelledBy cancelledBy,
        @Nullable Long patientId,
        @Nullable String patientName,
        @Nullable String patientPhone) {

    public boolean isActive() {
        return status != AppointmentStatus.CANCELLED;
    }
}
