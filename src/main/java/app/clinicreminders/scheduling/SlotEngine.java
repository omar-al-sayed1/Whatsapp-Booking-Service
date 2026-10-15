package app.clinicreminders.scheduling;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Computes free slots. Pure: no Spring, no database, no system clock.
 *
 * <p>The slot grid is laid out per local date in the clinic's zone and only then converted to
 * instants, so daylight saving changes never shift or drop a day. Around a change:
 * <ul>
 *   <li>a local start time that does not exist (spring gap) is skipped, not shifted, because
 *       shifting would duplicate the next slot;</li>
 *   <li>a local start time that happens twice (autumn overlap) is offered once, at the earlier
 *       offset;</li>
 *   <li>a slot always lasts exactly the slot length in real time.</li>
 * </ul>
 * Existing appointments block every slot they overlap, so appointments booked under an older slot
 * length still block correctly.
 */
public final class SlotEngine {

    private SlotEngine() {
    }

    /**
     * Free slots for the local dates {@code from} to {@code to} (inclusive) in the schedule's zone.
     *
     * @param busy existing active appointments; any overlap blocks a slot
     * @param now  slots starting at or before this instant are not offered
     */
    public static List<Slot> freeSlots(DoctorSchedule schedule, LocalDate from, LocalDate to,
                                       Collection<TimeRange> busy, Instant now) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to must not be before from: " + from + " - " + to);
        }
        ZoneRules rules = schedule.zone().getRules();
        long slotSeconds = schedule.slotLength().toSeconds();
        List<Slot> slots = new ArrayList<>();

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            for (TimeWindow window : schedule.windowsOn(date)) {
                // Seconds of the day rather than LocalTime arithmetic, which would wrap past midnight.
                long windowEnd = window.end().toSecondOfDay();
                for (long start = window.start().toSecondOfDay(); start + slotSeconds <= windowEnd; start += slotSeconds) {
                    LocalDateTime localStart = date.atTime(LocalTime.ofSecondOfDay(start));
                    ZoneOffsetTransition transition = rules.getTransition(localStart);
                    if (transition != null && transition.isGap()) {
                        continue;
                    }
                    // atZone picks the earlier offset when the local time happens twice.
                    Instant slotStart = localStart.atZone(schedule.zone()).toInstant();
                    Instant slotEnd = slotStart.plus(schedule.slotLength());
                    if (slotStart.isAfter(now) && busy.stream().noneMatch(b -> b.overlaps(slotStart, slotEnd))) {
                        slots.add(new Slot(slotStart, slotEnd));
                    }
                }
            }
        }
        return slots;
    }
}
