package app.clinicreminders.appointment;

import java.time.Instant;

/** The doctor already has an active appointment starting at that time. */
public class SlotTakenException extends RuntimeException {

    public SlotTakenException(long doctorId, Instant startsAt) {
        super("Doctor " + doctorId + " already has an appointment at " + startsAt);
    }
}
