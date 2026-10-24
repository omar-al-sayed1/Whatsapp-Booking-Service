package app.clinicreminders.report;

import java.time.DayOfWeek;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * How full each hour of the week was in a month: the appointments booked in it (not cancelled)
 * against the slots the doctors' hours offered in it, so the clinic sees which hours fill up and
 * which stay empty. Open slots follow the doctors' weekly hours as they are now, with the month's
 * days off and special hours.
 *
 * @param hours the hours shown, earliest to latest: every hour with opening hours or a booking
 * @param days  the weekdays with opening hours or a booking, Monday first
 */
public record BusyHours(List<Integer> hours, List<Day> days) {

    /** One weekday's hours, one cell per hour of {@link #hours()}. */
    public record Day(DayOfWeek day, List<Cell> cells) {
    }

    /**
     * @param booked appointments starting in this hour on this weekday, over the month
     * @param open   slots the doctors' hours offered then, over the month
     */
    public record Cell(DayOfWeek day, int hour, long booked, long open) {

        /** Booked out of open, rounded; over 100 with squeeze-ins; null outside opening hours. */
        public @Nullable Integer percent() {
            return open == 0 ? null : (int) Math.round(100.0 * booked / open);
        }

        /**
         * For the colour: "closed" (no hours, nothing booked), "outside" (booked outside the hours),
         * then "empty", "quiet", "half", "busy" and "full" as it fills up.
         */
        public String level() {
            Integer percent = percent();
            if (percent == null) {
                return booked == 0 ? "closed" : "outside";
            }
            if (percent == 0) {
                return "empty";
            }
            return percent < 40 ? "quiet" : percent < 70 ? "half" : percent < 90 ? "busy" : "full";
        }
    }

    public boolean isEmpty() {
        return days.isEmpty();
    }

    /** The fullest open hour, if any hour was open. */
    public Optional<Cell> busiest() {
        return openCells().max(Comparator.comparing(Cell::percent).thenComparing(Cell::booked));
    }

    /** The emptiest open hour, if any hour was open: the time to offer, or to stop opening. */
    public Optional<Cell> quietest() {
        return openCells().min(Comparator.comparing(Cell::percent).thenComparing(Cell::open, Comparator.reverseOrder()));
    }

    private Stream<Cell> openCells() {
        return days.stream().flatMap(day -> day.cells().stream()).filter(cell -> cell.open() > 0);
    }
}
