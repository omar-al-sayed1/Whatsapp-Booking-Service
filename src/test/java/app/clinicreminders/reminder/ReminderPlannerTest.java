package app.clinicreminders.reminder;

import static app.clinicreminders.reminder.ReminderKind.H2;
import static app.clinicreminders.reminder.ReminderKind.H24;
import static app.clinicreminders.reminder.ReminderStatus.PENDING;
import static app.clinicreminders.reminder.ReminderStatus.SKIPPED;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import app.clinicreminders.reminder.ReminderPlanner.Planned;
import app.clinicreminders.reminder.ReminderPlanner.Settings;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReminderPlannerTest {

    private static final ZoneId BEIRUT = ZoneId.of("Asia/Beirut");
    private static final QuietHours NIGHT = new QuietHours(LocalTime.of(21, 0), LocalTime.of(8, 0));
    private static final Settings DEFAULTS = new Settings(BEIRUT, NIGHT, true, true);
    private static final Instant LONG_BEFORE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void h24AtTheSameTimeTheDayBeforeAndH2TwoHoursBefore() {
        // Tuesday 3 November 2026, 10:30 in Beirut (UTC+2).
        List<Planned> plan = ReminderPlanner.plan(beirut(2026, 11, 3, 10, 30), LONG_BEFORE, DEFAULTS);

        assertThat(plan).containsExactly(
                new Planned(H24, Instant.parse("2026-11-02T08:30:00Z"), PENDING),
                new Planned(H2, Instant.parse("2026-11-03T06:30:00Z"), PENDING));
    }

    @Nested
    class QuietHoursRules {

        @Test
        void anEveningH24MovesToJustBeforeQuietHours() {
            // 21:30 Tuesday: H24 would be 21:30 Monday, in quiet hours; it goes at 20:59 Monday.
            List<Planned> plan = ReminderPlanner.plan(beirut(2026, 11, 3, 21, 30), LONG_BEFORE, DEFAULTS);

            assertThat(plan.getFirst()).isEqualTo(new Planned(H24, beirut(2026, 11, 2, 20, 59), PENDING));
        }

        @Test
        void anEarlyMorningH24WaitsForQuietHoursToEndSoItStaysTheDayBefore() {
            // 07:30 Tuesday: 20:59 Sunday would be two days ahead, so it goes at 08:00 Monday.
            List<Planned> plan = ReminderPlanner.plan(beirut(2026, 11, 3, 7, 30), LONG_BEFORE, DEFAULTS);

            assertThat(plan).containsExactly(
                    new Planned(H24, beirut(2026, 11, 2, 8, 0), PENDING),
                    new Planned(H2, beirut(2026, 11, 3, 5, 30), SKIPPED));
        }

        @Test
        void anH2InQuietHoursIsSkipped() {
            List<Planned> plan = ReminderPlanner.plan(beirut(2026, 11, 3, 22, 30), LONG_BEFORE, DEFAULTS);

            assertThat(plan.get(1)).isEqualTo(new Planned(H2, beirut(2026, 11, 3, 20, 30), PENDING));
            assertThat(ReminderPlanner.plan(beirut(2026, 11, 3, 23, 30), LONG_BEFORE, DEFAULTS).get(1).status())
                    .isEqualTo(SKIPPED);
        }

        @Test
        void aDaytimeQuietWindowWorksToo() {
            var siesta = new Settings(BEIRUT, new QuietHours(LocalTime.of(13, 0), LocalTime.of(15, 0)), true, true);

            List<Planned> plan = ReminderPlanner.plan(beirut(2026, 11, 3, 16, 0), LONG_BEFORE, siesta);

            assertThat(plan).containsExactly(
                    new Planned(H24, beirut(2026, 11, 2, 16, 0), PENDING),
                    new Planned(H2, beirut(2026, 11, 3, 14, 0), SKIPPED));
            assertThat(ReminderPlanner.plan(beirut(2026, 11, 3, 14, 0), LONG_BEFORE, siesta).getFirst())
                    .isEqualTo(new Planned(H24, beirut(2026, 11, 2, 12, 59), PENDING));
        }
    }

    @Nested
    class ShortNotice {

        @Test
        void bookedLessThan24HoursAheadSkipsTheH24() {
            Instant now = beirut(2026, 11, 2, 12, 0);

            List<Planned> plan = ReminderPlanner.plan(beirut(2026, 11, 3, 10, 30), now, DEFAULTS);

            assertThat(plan).extracting(Planned::status).containsExactly(SKIPPED, PENDING);
        }

        @Test
        void lessThan24HoursAheadSkipsTheH24EvenIfItsMovedTimeIsStillAhead() {
            // 07:30 Tuesday's H24 moves to 08:00 Monday, but at 07:45 Monday the booking is under 24 hours ahead.
            Instant now = beirut(2026, 11, 2, 7, 45);

            assertThat(ReminderPlanner.plan(beirut(2026, 11, 3, 7, 30), now, DEFAULTS).getFirst().status())
                    .isEqualTo(SKIPPED);
        }

        @Test
        void bookedLessThan2HoursAheadSkipsBoth() {
            Instant start = beirut(2026, 11, 3, 10, 30);

            assertThat(ReminderPlanner.plan(start, start.minusSeconds(90 * 60), DEFAULTS))
                    .extracting(Planned::status).containsExactly(SKIPPED, SKIPPED);
        }
    }

    @Test
    void kindsTheClinicSwitchedOffAreNotPlanned() {
        var onlyH2 = new Settings(BEIRUT, NIGHT, false, true);
        var none = new Settings(BEIRUT, NIGHT, false, false);

        assertThat(ReminderPlanner.plan(beirut(2026, 11, 3, 10, 30), LONG_BEFORE, onlyH2))
                .extracting(Planned::kind).containsExactly(H2);
        assertThat(ReminderPlanner.plan(beirut(2026, 11, 3, 10, 30), LONG_BEFORE, none)).isEmpty();
    }

    /** Clocks go back at 00:00 on Sunday 25 October 2026 and forward at 00:00 on Sunday 29 March 2026. */
    @Nested
    class DaylightSaving {

        @Test
        void acrossTheAutumnChangeTheH24Is25HoursAheadAtTheSameLocalTime() {
            // Sunday 25 October 10:00 (UTC+2) -> Saturday 24 October 10:00 (UTC+3).
            Instant start = Instant.parse("2026-10-25T08:00:00Z");

            List<Planned> plan = ReminderPlanner.plan(start, LONG_BEFORE, DEFAULTS);

            assertThat(plan.getFirst().dueAt()).isEqualTo(Instant.parse("2026-10-24T07:00:00Z"));
            assertThat(LocalDateTime.ofInstant(plan.getFirst().dueAt(), BEIRUT)).isEqualTo(LocalDateTime.of(2026, 10, 24, 10, 0));
            assertThat(plan.get(1).dueAt()).isEqualTo(Instant.parse("2026-10-25T06:00:00Z"));
        }

        @Test
        void acrossTheSpringChangeTheH24Is23HoursAheadAtTheSameLocalTime() {
            // Sunday 29 March 10:00 (UTC+3) -> Saturday 28 March 10:00 (UTC+2).
            Instant start = Instant.parse("2026-03-29T07:00:00Z");

            List<Planned> plan = ReminderPlanner.plan(start, LONG_BEFORE, DEFAULTS);

            assertThat(plan.getFirst().dueAt()).isEqualTo(Instant.parse("2026-03-28T08:00:00Z"));
            assertThat(plan.get(1)).isEqualTo(new Planned(H2, Instant.parse("2026-03-29T05:00:00Z"), PENDING));
        }

        @Test
        void anH2InTheRepeatedHourIsStillInQuietHours() {
            // 01:00 Sunday 25 October (UTC+2): H2 is 23:00 Saturday, local time, in quiet hours.
            List<Planned> plan = ReminderPlanner.plan(Instant.parse("2026-10-24T23:00:00Z"), LONG_BEFORE, DEFAULTS);

            assertThat(plan.get(1).status()).isEqualTo(SKIPPED);
        }
    }

    private static Instant beirut(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(BEIRUT).toInstant();
    }
}
