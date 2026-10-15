package app.clinicreminders.scheduling;

import java.time.Instant;
import java.util.Objects;

/** A busy period on the timeline, e.g. an existing appointment. Half-open: {@code [start, end)}. */
public record TimeRange(Instant start, Instant end) {

    public TimeRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("start must be before end: " + start + " - " + end);
        }
    }

    public boolean overlaps(Instant otherStart, Instant otherEnd) {
        return start.isBefore(otherEnd) && otherStart.isBefore(end);
    }
}
