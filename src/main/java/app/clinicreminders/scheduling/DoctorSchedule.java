package app.clinicreminders.scheduling;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the slot engine needs to know about a doctor's working time: weekly hours, per-date
 * exceptions, the slot length and the clinic's zone. An exception replaces the weekly hours for its
 * date; an exception with no windows means closed.
 */
public record DoctorSchedule(
        ZoneId zone,
        Duration slotLength,
        Map<DayOfWeek, List<TimeWindow>> weeklyHours,
        Map<LocalDate, List<TimeWindow>> exceptions) {

    public DoctorSchedule {
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(slotLength, "slotLength");
        if (slotLength.isNegative() || slotLength.isZero()) {
            throw new IllegalArgumentException("slotLength must be positive: " + slotLength);
        }
        weeklyHours = Map.copyOf(weeklyHours);
        exceptions = Map.copyOf(exceptions);
    }

    public static Builder builder(ZoneId zone, Duration slotLength) {
        return new Builder(zone, slotLength);
    }

    /** The opening windows on {@code date}, sorted, with overlapping or touching windows merged. */
    public List<TimeWindow> windowsOn(LocalDate date) {
        List<TimeWindow> windows = exceptions.containsKey(date)
                ? exceptions.get(date)
                : weeklyHours.getOrDefault(date.getDayOfWeek(), List.of());
        return merge(windows);
    }

    private static List<TimeWindow> merge(List<TimeWindow> windows) {
        if (windows.size() < 2) {
            return windows;
        }
        List<TimeWindow> sorted = windows.stream().sorted(Comparator.comparing(TimeWindow::start)).toList();
        List<TimeWindow> merged = new ArrayList<>();
        TimeWindow current = sorted.getFirst();
        for (TimeWindow next : sorted.subList(1, sorted.size())) {
            if (next.start().isAfter(current.end())) {
                merged.add(current);
                current = next;
            } else if (next.end().isAfter(current.end())) {
                current = new TimeWindow(current.start(), next.end());
            }
        }
        merged.add(current);
        return merged;
    }

    public static final class Builder {

        private final ZoneId zone;
        private final Duration slotLength;
        private final Map<DayOfWeek, List<TimeWindow>> weeklyHours = new EnumMap<>(DayOfWeek.class);
        private final Map<LocalDate, List<TimeWindow>> exceptions = new HashMap<>();

        private Builder(ZoneId zone, Duration slotLength) {
            this.zone = zone;
            this.slotLength = slotLength;
        }

        public Builder weekly(DayOfWeek day, LocalTime start, LocalTime end) {
            weeklyHours.computeIfAbsent(day, d -> new ArrayList<>()).add(new TimeWindow(start, end));
            return this;
        }

        public Builder closedOn(LocalDate date) {
            exceptions.put(date, List.of());
            return this;
        }

        public Builder hoursOn(LocalDate date, LocalTime start, LocalTime end) {
            exceptions.put(date, List.of(new TimeWindow(start, end)));
            return this;
        }

        public DoctorSchedule build() {
            Map<DayOfWeek, List<TimeWindow>> weekly = new EnumMap<>(DayOfWeek.class);
            weeklyHours.forEach((day, windows) -> weekly.put(day, List.copyOf(windows)));
            return new DoctorSchedule(zone, slotLength, weekly, exceptions);
        }
    }
}
