package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class ReportViewTest {

    private static final ZoneId BEIRUT = ZoneId.of("Asia/Beirut");
    private static final Instant CREATED = Instant.parse("2026-09-10T08:00:00Z");
    /** 31 October, 23:30 in Beirut: still October there, already November in UTC+3. */
    private static final Instant NOW = Instant.parse("2026-10-31T21:30:00Z");

    @Test
    void theMonthShownStaysBetweenTheClinicsFirstMonthAndNowInItsZone() {
        assertThat(ReportView.month(null, CREATED, BEIRUT, NOW)).isEqualTo(YearMonth.of(2026, 10));
        assertThat(ReportView.month("2026-09", CREATED, BEIRUT, NOW)).isEqualTo(YearMonth.of(2026, 9));
        assertThat(ReportView.month("2026-11", CREATED, BEIRUT, NOW)).isEqualTo(YearMonth.of(2026, 10));
        assertThat(ReportView.month("2026-01", CREATED, BEIRUT, NOW)).isEqualTo(YearMonth.of(2026, 9));
        assertThat(ReportView.month("October", CREATED, BEIRUT, NOW)).isEqualTo(YearMonth.of(2026, 10));
        assertThat(ReportView.month("2026-08", null, BEIRUT, NOW)).as("no creation date: this month only")
                .isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void dollarsAndADashWhenNothingCouldBePriced() {
        assertThat(ReportView.usd(new BigDecimal("4.1234"))).isEqualTo("$4.12");
        assertThat(ReportView.usd(new BigDecimal("0.0040"))).isEqualTo("< $0.01");
        assertThat(ReportView.usd(BigDecimal.ZERO)).isEqualTo("$0.00");
        assertThat(ReportView.cost(BigDecimal.ZERO, 12, 12)).as("no rate was set").isEqualTo("—");
        assertThat(ReportView.cost(new BigDecimal("0.0314"), 12, 10)).isEqualTo("$0.03");
        assertThat(ReportView.cost(BigDecimal.ZERO, 0, 0)).isEqualTo("$0.00");
    }
}
