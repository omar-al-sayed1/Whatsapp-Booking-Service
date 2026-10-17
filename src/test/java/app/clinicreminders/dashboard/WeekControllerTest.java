package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** Which day a phone lands on. The week of Monday 12 October 2026; today is Wednesday the 14th. */
class WeekControllerTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    @Test
    void theDayJustChangedWins() {
        assertThat(WeekController.focusDay("2026-10-18", MONDAY, TODAY)).isEqualTo("2026-10-18");
        assertThat(WeekController.focusDay("2026-10-12", MONDAY, TODAY)).isEqualTo("2026-10-12");
    }

    @Test
    void otherwiseTodayWhenItIsThisWeek() {
        assertThat(WeekController.focusDay(null, MONDAY, TODAY)).isEqualTo("2026-10-14");
        assertThat(WeekController.focusDay("2026-10-19", MONDAY, TODAY)).as("a day of another week").isEqualTo("2026-10-14");
        assertThat(WeekController.focusDay("not a date", MONDAY, TODAY)).isEqualTo("2026-10-14");
    }

    @Test
    void anotherWeekStartsAtTheTop() {
        assertThat(WeekController.focusDay(null, MONDAY.plusWeeks(1), TODAY)).isNull();
        assertThat(WeekController.focusDay("2026-10-21", MONDAY.plusWeeks(1), TODAY)).isEqualTo("2026-10-21");
    }
}
