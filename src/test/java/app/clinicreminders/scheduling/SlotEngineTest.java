package app.clinicreminders.scheduling;

import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneOffsetTransition;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SlotEngineTest {

    private static final ZoneId BEIRUT = ZoneId.of("Asia/Beirut");
    private static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");

    /** An ordinary Monday, after the October change (EET, UTC+2). */
    private static final LocalDate MON = LocalDate.of(2026, 11, 2);
    private static final LocalDate TUE = MON.plusDays(1);
    private static final LocalDate SUN = MON.minusDays(1);

    @Nested
    class RegularDays {

        @Test
        void fillsTheWindowWithSlotsOfTheDoctorsLength() {
            var schedule = schedule(30).weekly(MONDAY, t("09:00"), t("12:00")).build();

            var slots = freeSlots(schedule, MON, MON);

            assertThat(times(slots)).containsExactly("09:00", "09:30", "10:00", "10:30", "11:00", "11:30");
            assertThat(slots.getFirst()).isEqualTo(
                    new Slot(Instant.parse("2026-11-02T07:00:00Z"), Instant.parse("2026-11-02T07:30:00Z")));
            assertWellFormed(slots, 30);
        }

        @Test
        void timeLeftBeforeClosingThatIsShorterThanASlotIsNotOffered() {
            var schedule = schedule(45).weekly(MONDAY, t("09:00"), t("11:00")).build();

            assertThat(times(freeSlots(schedule, MON, MON))).containsExactly("09:00", "09:45");
        }

        @Test
        void splitShiftOffersBothWindows() {
            var schedule = schedule(60)
                    .weekly(MONDAY, t("15:00"), t("17:00"))
                    .weekly(MONDAY, t("09:00"), t("12:00"))
                    .build();

            assertThat(times(freeSlots(schedule, MON, MON)))
                    .containsExactly("09:00", "10:00", "11:00", "15:00", "16:00");
        }

        @Test
        void overlappingWindowsAreMergedSoSlotsNeverOverlap() {
            var schedule = schedule(30)
                    .weekly(MONDAY, t("09:00"), t("11:00"))
                    .weekly(MONDAY, t("10:15"), t("12:00"))
                    .build();

            var slots = freeSlots(schedule, MON, MON);

            assertThat(times(slots)).containsExactly("09:00", "09:30", "10:00", "10:30", "11:00", "11:30");
            assertWellFormed(slots, 30);
        }

        @Test
        void touchingWindowsAreMergedIntoOneGrid() {
            var schedule = schedule(30)
                    .weekly(MONDAY, t("09:00"), t("10:45"))
                    .weekly(MONDAY, t("10:45"), t("12:00"))
                    .build();

            assertThat(times(freeSlots(schedule, MON, MON)))
                    .containsExactly("09:00", "09:30", "10:00", "10:30", "11:00", "11:30");
        }

        @Test
        void dayWithoutWeeklyHoursIsClosed() {
            var schedule = schedule(30).weekly(MONDAY, t("09:00"), t("12:00")).build();

            assertThat(freeSlots(schedule, SUN, SUN)).isEmpty();
        }

        @Test
        void slotsAreSortedAcrossDays() {
            var schedule = schedule(60)
                    .weekly(TUESDAY, t("09:00"), t("10:00"))
                    .weekly(MONDAY, t("09:00"), t("11:00"))
                    .build();

            assertThat(dateTimes(freeSlots(schedule, SUN, TUE)))
                    .containsExactly("2026-11-02 09:00", "2026-11-02 10:00", "2026-11-03 09:00");
        }
    }

    @Nested
    class Exceptions {

        @Test
        void closedDayRemovesTheWholeDay() {
            var schedule = schedule(60)
                    .weekly(MONDAY, t("09:00"), t("11:00"))
                    .weekly(TUESDAY, t("09:00"), t("11:00"))
                    .closedOn(MON)
                    .build();

            assertThat(dateTimes(freeSlots(schedule, MON, TUE)))
                    .containsExactly("2026-11-03 09:00", "2026-11-03 10:00");
        }

        @Test
        void exceptionHoursReplaceTheWeeklyHours() {
            var schedule = schedule(30)
                    .weekly(MONDAY, t("09:00"), t("12:00"))
                    .hoursOn(MON, t("14:00"), t("15:00"))
                    .build();

            assertThat(times(freeSlots(schedule, MON, MON))).containsExactly("14:00", "14:30");
        }

        @Test
        void exceptionCanOpenADayThatIsNormallyClosed() {
            var schedule = schedule(30)
                    .weekly(MONDAY, t("09:00"), t("12:00"))
                    .hoursOn(SUN, t("10:00"), t("11:00"))
                    .build();

            assertThat(dateTimes(freeSlots(schedule, SUN, SUN)))
                    .containsExactly("2026-11-01 10:00", "2026-11-01 10:30");
        }
    }

    @Nested
    class ExistingAppointments {

        private final DoctorSchedule schedule = schedule(30).weekly(MONDAY, t("09:00"), t("11:00")).build();

        @Test
        void appointmentOnASlotBlocksOnlyThatSlot() {
            var busy = List.of(busy(MON, "09:30", "10:00"));

            assertThat(times(freeSlots(schedule, MON, MON, busy, LONG_AGO)))
                    .containsExactly("09:00", "10:00", "10:30");
        }

        @Test
        void appointmentSpanningTwoSlotsBlocksBoth() {
            var busy = List.of(busy(MON, "09:15", "10:00"));

            assertThat(times(freeSlots(schedule, MON, MON, busy, LONG_AGO)))
                    .containsExactly("10:00", "10:30");
        }

        @Test
        void appointmentThatOnlyTouchesASlotDoesNotBlockIt() {
            var busy = List.of(busy(MON, "08:30", "09:00"), busy(MON, "11:00", "11:30"));

            assertThat(times(freeSlots(schedule, MON, MON, busy, LONG_AGO)))
                    .containsExactly("09:00", "09:30", "10:00", "10:30");
        }
    }

    @Nested
    class SlotLengthChange {

        @Test
        void appointmentsOnTheOldGridBlockEveryOverlappingSlotOnTheNewGrid() {
            // Booked when slots were 15 minutes; the doctor now uses 20-minute slots.
            var busy = List.of(busy(MON, "09:15", "09:30"));
            var schedule = schedule(20).weekly(MONDAY, t("09:00"), t("11:00")).build();

            var slots = freeSlots(schedule, MON, MON, busy, LONG_AGO);

            assertThat(times(slots)).containsExactly("09:40", "10:00", "10:20", "10:40");
            assertWellFormed(slots, 20);
        }

        @Test
        void longerAppointmentsFromBeforeAShorterSlotLengthStillBlock() {
            // Booked with 30-minute slots; the doctor now uses 20-minute slots.
            var busy = List.of(busy(MON, "10:00", "10:30"));
            var schedule = schedule(20).weekly(MONDAY, t("10:00"), t("11:00")).build();

            assertThat(times(freeSlots(schedule, MON, MON, busy, LONG_AGO))).containsExactly("10:40");
        }
    }

    @Nested
    class Now {

        @Test
        void slotsThatStartedOrStartRightNowAreNotOffered() {
            var schedule = schedule(30).weekly(MONDAY, t("09:00"), t("11:00")).build();
            Instant now = local(MON, "09:30");

            assertThat(times(freeSlots(schedule, MON, MON, List.of(), now))).containsExactly("10:00", "10:30");
        }

        @Test
        void daysEntirelyInThePastAreEmpty() {
            var schedule = schedule(30).weekly(MONDAY, t("09:00"), t("11:00")).build();

            assertThat(freeSlots(schedule, MON, MON, List.of(), local(TUE, "08:00"))).isEmpty();
        }
    }

    @Nested
    class InvalidInput {

        @Test
        void rejectsARangeThatEndsBeforeItStarts() {
            var schedule = schedule(30).weekly(MONDAY, t("09:00"), t("11:00")).build();

            assertThatIllegalArgumentException().isThrownBy(() -> freeSlots(schedule, TUE, MON));
        }

        @Test
        void rejectsAWindowThatEndsBeforeItStarts() {
            assertThatIllegalArgumentException().isThrownBy(() -> new TimeWindow(t("12:00"), t("09:00")));
        }

        @Test
        void rejectsANonPositiveSlotLength() {
            assertThatIllegalArgumentException().isThrownBy(() -> schedule(0).build());
        }
    }

    /**
     * Asia/Beirut: clocks go forward at 00:00 on the last Sunday of March (Sunday 00:00-00:59 does
     * not exist) and back at 00:00 on the last Sunday of October (Saturday 23:00-23:59 happens twice).
     */
    @Nested
    class BeirutDaylightSaving {

        private static final LocalDate SAT_BEFORE_SPRING = LocalDate.of(2026, 3, 28);
        private static final LocalDate SPRING_SUNDAY = LocalDate.of(2026, 3, 29);
        private static final LocalDate MON_AFTER_SPRING = LocalDate.of(2026, 3, 30);
        private static final LocalDate SAT_BEFORE_FALL = LocalDate.of(2026, 10, 24);
        private static final LocalDate MON_AFTER_FALL = LocalDate.of(2026, 10, 26);

        @Test
        void tzdataHasTheTransitionsTheseTestsAssume() {
            ZoneOffsetTransition spring = BEIRUT.getRules()
                    .getTransition(LocalDateTime.of(SPRING_SUNDAY, LocalTime.of(0, 30)));
            assertThat(spring).isNotNull();
            assertThat(spring.isGap()).isTrue();
            assertThat(spring.getInstant()).isEqualTo(Instant.parse("2026-03-28T22:00:00Z"));
            assertThat(spring.getOffsetBefore()).isEqualTo(ZoneOffset.ofHours(2));
            assertThat(spring.getOffsetAfter()).isEqualTo(ZoneOffset.ofHours(3));

            ZoneOffsetTransition fall = BEIRUT.getRules()
                    .getTransition(LocalDateTime.of(SAT_BEFORE_FALL, LocalTime.of(23, 30)));
            assertThat(fall).isNotNull();
            assertThat(fall.isOverlap()).isTrue();
            assertThat(fall.getInstant()).isEqualTo(Instant.parse("2026-10-24T21:00:00Z"));
            assertThat(fall.getOffsetBefore()).isEqualTo(ZoneOffset.ofHours(3));
            assertThat(fall.getOffsetAfter()).isEqualTo(ZoneOffset.ofHours(2));
        }

        @Test
        void springForwardMovesTheSameLocalSlotOneHourEarlierInUtc() {
            var schedule = schedule(30)
                    .weekly(SATURDAY, t("09:00"), t("10:00"))
                    .weekly(MONDAY, t("09:00"), t("10:00"))
                    .build();

            var slots = freeSlots(schedule, SAT_BEFORE_SPRING, MON_AFTER_SPRING);

            assertThat(slots).extracting(Slot::start).containsExactly(
                    Instant.parse("2026-03-28T07:00:00Z"), Instant.parse("2026-03-28T07:30:00Z"),
                    Instant.parse("2026-03-30T06:00:00Z"), Instant.parse("2026-03-30T06:30:00Z"));
            assertWellFormed(slots, 30);
        }

        @Test
        void fallBackMovesTheSameLocalSlotOneHourLaterInUtc() {
            var schedule = schedule(30)
                    .weekly(SATURDAY, t("09:00"), t("10:00"))
                    .weekly(MONDAY, t("09:00"), t("10:00"))
                    .build();

            var slots = freeSlots(schedule, SAT_BEFORE_FALL, MON_AFTER_FALL);

            assertThat(slots).extracting(Slot::start).containsExactly(
                    Instant.parse("2026-10-24T06:00:00Z"), Instant.parse("2026-10-24T06:30:00Z"),
                    Instant.parse("2026-10-26T07:00:00Z"), Instant.parse("2026-10-26T07:30:00Z"));
            assertWellFormed(slots, 30);
        }

        @Test
        void everyDayAppearsExactlyOnceAcrossTheSpringChange() {
            var schedule = everyDay("09:00", "10:00", 60);

            var slots = freeSlots(schedule, LocalDate.of(2026, 3, 25), LocalDate.of(2026, 4, 1));

            assertThat(dateTimes(slots)).containsExactly(
                    "2026-03-25 09:00", "2026-03-26 09:00", "2026-03-27 09:00", "2026-03-28 09:00",
                    "2026-03-29 09:00", "2026-03-30 09:00", "2026-03-31 09:00", "2026-04-01 09:00");
            assertThat(slots).extracting(Slot::start).containsExactly(
                    Instant.parse("2026-03-25T07:00:00Z"), Instant.parse("2026-03-26T07:00:00Z"),
                    Instant.parse("2026-03-27T07:00:00Z"), Instant.parse("2026-03-28T07:00:00Z"),
                    Instant.parse("2026-03-29T06:00:00Z"), Instant.parse("2026-03-30T06:00:00Z"),
                    Instant.parse("2026-03-31T06:00:00Z"), Instant.parse("2026-04-01T06:00:00Z"));
        }

        @Test
        void everyDayAppearsExactlyOnceAcrossTheFallChange() {
            var schedule = everyDay("09:00", "10:00", 60);

            var slots = freeSlots(schedule, LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 28));

            assertThat(dateTimes(slots)).containsExactly(
                    "2026-10-21 09:00", "2026-10-22 09:00", "2026-10-23 09:00", "2026-10-24 09:00",
                    "2026-10-25 09:00", "2026-10-26 09:00", "2026-10-27 09:00", "2026-10-28 09:00");
            assertThat(slots).extracting(Slot::start).containsExactly(
                    Instant.parse("2026-10-21T06:00:00Z"), Instant.parse("2026-10-22T06:00:00Z"),
                    Instant.parse("2026-10-23T06:00:00Z"), Instant.parse("2026-10-24T06:00:00Z"),
                    Instant.parse("2026-10-25T07:00:00Z"), Instant.parse("2026-10-26T07:00:00Z"),
                    Instant.parse("2026-10-27T07:00:00Z"), Instant.parse("2026-10-28T07:00:00Z"));
        }

        @Test
        void slotsInTheSkippedHourAreNotOfferedOrShifted() {
            // Shifting 00:00 and 00:30 forward would duplicate the 01:00 and 01:30 slots.
            var schedule = schedule(30).hoursOn(SPRING_SUNDAY, t("00:00"), t("02:00")).build();

            var slots = freeSlots(schedule, SPRING_SUNDAY, SPRING_SUNDAY);

            assertThat(slots).containsExactly(
                    new Slot(Instant.parse("2026-03-28T22:00:00Z"), Instant.parse("2026-03-28T22:30:00Z")),
                    new Slot(Instant.parse("2026-03-28T22:30:00Z"), Instant.parse("2026-03-28T23:00:00Z")));
            assertThat(times(slots)).containsExactly("01:00", "01:30");
        }

        @Test
        void slotsInTheRepeatedHourAreOfferedOnceAtTheEarlierOffset() {
            var schedule = schedule(30).hoursOn(SAT_BEFORE_FALL, t("22:00"), t("23:59")).build();

            var slots = freeSlots(schedule, SAT_BEFORE_FALL, SAT_BEFORE_FALL);

            assertThat(slots).containsExactly(
                    new Slot(Instant.parse("2026-10-24T19:00:00Z"), Instant.parse("2026-10-24T19:30:00Z")),
                    new Slot(Instant.parse("2026-10-24T19:30:00Z"), Instant.parse("2026-10-24T20:00:00Z")),
                    new Slot(Instant.parse("2026-10-24T20:00:00Z"), Instant.parse("2026-10-24T20:30:00Z")));
            assertWellFormed(slots, 30);
        }

        @Test
        void appointmentStoredInUtcBlocksTheRightLocalSlotAfterTheChange() {
            // Booked on 14 October (UTC+3) for 10:00 on Monday 26 October (UTC+2), stored as 08:00Z.
            // Converting with the offset at booking time would block 11:00 instead of 10:00.
            var schedule = schedule(30).weekly(MONDAY, t("09:00"), t("11:00")).build();
            var busy = List.of(new TimeRange(Instant.parse("2026-10-26T08:00:00Z"), Instant.parse("2026-10-26T08:30:00Z")));
            Instant bookedAt = Instant.parse("2026-10-14T09:00:00Z");

            var slots = freeSlots(schedule, MON_AFTER_FALL, MON_AFTER_FALL, busy, bookedAt);

            assertThat(times(slots)).containsExactly("09:00", "09:30", "10:30");
        }
    }

    // --- helpers ---

    private static DoctorSchedule.Builder schedule(int slotMinutes) {
        return DoctorSchedule.builder(BEIRUT, Duration.ofMinutes(slotMinutes));
    }

    private static DoctorSchedule everyDay(String start, String end, int slotMinutes) {
        var builder = schedule(slotMinutes);
        for (DayOfWeek day : DayOfWeek.values()) {
            builder.weekly(day, t(start), t(end));
        }
        return builder.build();
    }

    private static List<Slot> freeSlots(DoctorSchedule schedule, LocalDate from, LocalDate to) {
        return freeSlots(schedule, from, to, List.of(), LONG_AGO);
    }

    private static List<Slot> freeSlots(DoctorSchedule schedule, LocalDate from, LocalDate to,
                                        Collection<TimeRange> busy, Instant now) {
        return SlotEngine.freeSlots(schedule, from, to, busy, now);
    }

    private static LocalTime t(String time) {
        return LocalTime.parse(time);
    }

    private static Instant local(LocalDate date, String time) {
        return date.atTime(t(time)).atZone(BEIRUT).toInstant();
    }

    private static TimeRange busy(LocalDate date, String start, String end) {
        return new TimeRange(local(date, start), local(date, end));
    }

    private static List<String> times(List<Slot> slots) {
        return format(slots, "HH:mm");
    }

    private static List<String> dateTimes(List<Slot> slots) {
        return format(slots, "yyyy-MM-dd HH:mm");
    }

    private static List<String> format(List<Slot> slots, String pattern) {
        var formatter = DateTimeFormatter.ofPattern(pattern);
        return slots.stream().map(s -> s.start().atZone(BEIRUT).format(formatter)).toList();
    }

    /** Sorted, each exactly the slot length, and no two overlapping. */
    private static void assertWellFormed(List<Slot> slots, int slotMinutes) {
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            assertThat(Duration.between(slot.start(), slot.end())).isEqualTo(Duration.ofMinutes(slotMinutes));
            if (i > 0) {
                assertThat(slot.start()).isAfterOrEqualTo(slots.get(i - 1).end());
            }
        }
    }
}
