package app.clinicreminders.reminder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides when an appointment's reminders are due (SPEC section 5.1). Pure: no Spring, no clock.
 *
 * <ul>
 *   <li>H24: the same clinic-local time the day before (so DST changes don't shift it). In quiet
 *       hours it moves to one minute before they began; if that would leave the day before the
 *       appointment (early-morning appointments), it moves to the end of quiet hours instead, so
 *       the template's "tomorrow" stays true. Skipped when booked less than 24 hours ahead.</li>
 *   <li>H2: two hours before. Skipped in quiet hours or when booked less than 2 hours ahead.</li>
 * </ul>
 * Kinds the clinic has switched off get no reminder at all.
 */
public final class ReminderPlanner {

    private static final Duration DAY = Duration.ofHours(24);
    private static final Duration H2_LEAD = Duration.ofHours(2);

    public record Planned(ReminderKind kind, Instant dueAt, ReminderStatus status) {
    }

    public record Settings(ZoneId zone, QuietHours quietHours, boolean h24Enabled, boolean h2Enabled) {
    }

    private ReminderPlanner() {
    }

    public static List<Planned> plan(Instant startsAt, Instant now, Settings settings) {
        List<Planned> plan = new ArrayList<>();
        if (settings.h24Enabled()) {
            Instant due = h24Due(startsAt, settings);
            boolean tooLate = Duration.between(now, startsAt).compareTo(DAY) < 0 || !due.isAfter(now);
            plan.add(new Planned(ReminderKind.H24, due, tooLate ? ReminderStatus.SKIPPED : ReminderStatus.PENDING));
        }
        if (settings.h2Enabled()) {
            Instant due = startsAt.minus(H2_LEAD);
            boolean quiet = settings.quietHours().contains(due.atZone(settings.zone()).toLocalTime());
            boolean tooLate = !due.isAfter(now);
            plan.add(new Planned(ReminderKind.H2, due, quiet || tooLate ? ReminderStatus.SKIPPED : ReminderStatus.PENDING));
        }
        return plan;
    }

    static Instant h24Due(Instant startsAt, Settings settings) {
        LocalDateTime appointment = LocalDateTime.ofInstant(startsAt, settings.zone());
        LocalDateTime due = appointment.minusDays(1);
        QuietHours quiet = settings.quietHours();
        if (quiet.contains(due.toLocalTime())) {
            LocalDateTime beforeQuiet = quiet.periodStart(due).minusMinutes(1);
            due = beforeQuiet.toLocalDate().equals(appointment.toLocalDate().minusDays(1))
                    ? beforeQuiet
                    : quiet.periodEnd(due);
        }
        return due.atZone(settings.zone()).toInstant();
    }
}
