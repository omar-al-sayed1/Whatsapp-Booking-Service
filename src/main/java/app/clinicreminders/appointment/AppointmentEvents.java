package app.clinicreminders.appointment;

/**
 * Published by {@link AppointmentService} inside its transaction. Listeners that change data
 * (reminders) run in the same transaction; listeners that message the patient wait for the commit.
 */
public final class AppointmentEvents {

    private AppointmentEvents() {
    }

    /** Booked or moved: its reminders need (re)planning. */
    public record Scheduled(long clinicId, long appointmentId) {
    }

    public record Cancelled(long clinicId, long appointmentId, CancelledBy by) {
    }

    public enum CancelledBy {
        PATIENT,
        STAFF
    }
}
