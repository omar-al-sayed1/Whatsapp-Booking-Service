package app.clinicreminders.reminder;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * The clinic's no-messaging window, {@code [start, end)} in clinic time. It may cross midnight
 * (the default 21:00-08:00 does); equal start and end means no quiet hours.
 */
public record QuietHours(LocalTime start, LocalTime end) {

    public boolean contains(LocalTime time) {
        if (start.equals(end)) {
            return false;
        }
        if (crossesMidnight()) {
            return !time.isBefore(start) || time.isBefore(end);
        }
        return !time.isBefore(start) && time.isBefore(end);
    }

    /** When the quiet period containing {@code time} began. */
    public LocalDateTime periodStart(LocalDateTime time) {
        requireInside(time);
        boolean startedYesterday = crossesMidnight() && time.toLocalTime().isBefore(end);
        return (startedYesterday ? time.toLocalDate().minusDays(1) : time.toLocalDate()).atTime(start);
    }

    /** When the quiet period containing {@code time} ends. */
    public LocalDateTime periodEnd(LocalDateTime time) {
        requireInside(time);
        boolean endsTomorrow = crossesMidnight() && !time.toLocalTime().isBefore(start);
        return (endsTomorrow ? time.toLocalDate().plusDays(1) : time.toLocalDate()).atTime(end);
    }

    private boolean crossesMidnight() {
        return end.isBefore(start);
    }

    private void requireInside(LocalDateTime time) {
        if (!contains(time.toLocalTime())) {
            throw new IllegalArgumentException(time + " is not in quiet hours " + start + "-" + end);
        }
    }
}
