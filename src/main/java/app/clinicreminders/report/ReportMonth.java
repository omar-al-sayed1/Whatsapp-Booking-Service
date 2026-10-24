package app.clinicreminders.report;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * A calendar month of one clinic: from midnight on the 1st to midnight on the next 1st, in the
 * clinic's zone. Beirut changes clocks in March and October, so a month is not always a whole
 * number of 24-hour days.
 */
public record ReportMonth(YearMonth month, ZoneId zone) {

    public Instant from() {
        return month.atDay(1).atStartOfDay(zone).toInstant();
    }

    /** Exclusive. */
    public Instant to() {
        return month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
    }
}
