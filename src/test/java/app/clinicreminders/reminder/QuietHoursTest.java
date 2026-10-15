package app.clinicreminders.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDateTime;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class QuietHoursTest {

    private static final QuietHours NIGHT = new QuietHours(LocalTime.of(21, 0), LocalTime.of(8, 0));
    private static final QuietHours SIESTA = new QuietHours(LocalTime.of(13, 0), LocalTime.of(15, 0));

    @Test
    void overnightWindowIncludesItsStartAndExcludesItsEnd() {
        assertThat(NIGHT.contains(LocalTime.of(20, 59))).isFalse();
        assertThat(NIGHT.contains(LocalTime.of(21, 0))).isTrue();
        assertThat(NIGHT.contains(LocalTime.MIDNIGHT)).isTrue();
        assertThat(NIGHT.contains(LocalTime.of(7, 59))).isTrue();
        assertThat(NIGHT.contains(LocalTime.of(8, 0))).isFalse();
        assertThat(NIGHT.contains(LocalTime.NOON)).isFalse();
    }

    @Test
    void daytimeWindow() {
        assertThat(SIESTA.contains(LocalTime.of(12, 59))).isFalse();
        assertThat(SIESTA.contains(LocalTime.of(13, 0))).isTrue();
        assertThat(SIESTA.contains(LocalTime.of(14, 59))).isTrue();
        assertThat(SIESTA.contains(LocalTime.of(15, 0))).isFalse();
    }

    @Test
    void equalStartAndEndMeansNone() {
        var none = new QuietHours(LocalTime.of(21, 0), LocalTime.of(21, 0));

        assertThat(none.contains(LocalTime.of(21, 0))).isFalse();
        assertThat(none.contains(LocalTime.of(3, 0))).isFalse();
    }

    @Test
    void periodBoundsBeforeAndAfterMidnight() {
        LocalDateTime lateEvening = LocalDateTime.of(2026, 11, 2, 22, 30);
        LocalDateTime earlyMorning = LocalDateTime.of(2026, 11, 3, 6, 0);

        assertThat(NIGHT.periodStart(lateEvening)).isEqualTo(LocalDateTime.of(2026, 11, 2, 21, 0));
        assertThat(NIGHT.periodEnd(lateEvening)).isEqualTo(LocalDateTime.of(2026, 11, 3, 8, 0));
        assertThat(NIGHT.periodStart(earlyMorning)).isEqualTo(LocalDateTime.of(2026, 11, 2, 21, 0));
        assertThat(NIGHT.periodEnd(earlyMorning)).isEqualTo(LocalDateTime.of(2026, 11, 3, 8, 0));
        assertThat(SIESTA.periodStart(LocalDateTime.of(2026, 11, 2, 14, 0))).isEqualTo(LocalDateTime.of(2026, 11, 2, 13, 0));
        assertThat(SIESTA.periodEnd(LocalDateTime.of(2026, 11, 2, 14, 0))).isEqualTo(LocalDateTime.of(2026, 11, 2, 15, 0));
    }

    @Test
    void periodBoundsNeedATimeInside() {
        assertThatIllegalArgumentException().isThrownBy(() -> NIGHT.periodStart(LocalDateTime.of(2026, 11, 2, 12, 0)));
    }
}
