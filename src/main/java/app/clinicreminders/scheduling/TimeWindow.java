package app.clinicreminders.scheduling;

import java.time.LocalTime;
import java.util.Objects;

/** Opening hours on one day, in the clinic's wall-clock time. Half-open: {@code [start, end)}. */
public record TimeWindow(LocalTime start, LocalTime end) {

    public TimeWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("start must be before end: " + start + " - " + end);
        }
    }
}
