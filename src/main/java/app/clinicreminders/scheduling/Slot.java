package app.clinicreminders.scheduling;

import java.time.Instant;

/** A bookable slot. Half-open: {@code [start, end)}. Convert to the clinic's zone for display. */
public record Slot(Instant start, Instant end) {
}
