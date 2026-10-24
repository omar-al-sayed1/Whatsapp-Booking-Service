package app.clinicreminders.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import app.clinicreminders.report.BusyHours.Cell;
import app.clinicreminders.report.MonthlyReport.DoctorFigures;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

/** The report's arithmetic and its CSV, without a database. */
class ReportUnitTest {

    private static final ZoneId BEIRUT = ZoneId.of("Asia/Beirut");

    @Test
    void aMonthRunsFromLocalMidnightToLocalMidnightAcrossTheClockChange() {
        // Lebanon goes back from UTC+3 to UTC+2 on Sunday 25 October 2026.
        ReportMonth october = new ReportMonth(YearMonth.of(2026, 10), BEIRUT);

        assertThat(october.from()).isEqualTo(Instant.parse("2026-09-30T21:00:00Z"));
        assertThat(october.to()).isEqualTo(Instant.parse("2026-10-31T22:00:00Z"));
    }

    @Test
    void theNoShowRateCountsOnlyAppointmentsThatWereDue() {
        AppointmentFigures figures = new AppointmentFigures(20, 5, 9, 30, 9, 1, 3, 2, 1, 1, 5, 0);

        assertThat(figures.noShowPercent()).isEqualTo(10);
        assertThat(AppointmentFigures.NONE.noShowPercent()).isNull();
        assertThat(figures.plus(figures).slotsSaved()).isEqualTo(6);
    }

    @Test
    void busyHoursColourEachHourByHowFullItWas() {
        assertThat(new Cell(DayOfWeek.MONDAY, 9, 0, 0).level()).isEqualTo("closed");
        assertThat(new Cell(DayOfWeek.MONDAY, 9, 2, 0).level()).as("squeezed in outside the hours").isEqualTo("outside");
        assertThat(new Cell(DayOfWeek.MONDAY, 9, 0, 8).level()).isEqualTo("empty");
        assertThat(new Cell(DayOfWeek.MONDAY, 9, 2, 8).level()).isEqualTo("quiet");
        assertThat(new Cell(DayOfWeek.MONDAY, 9, 4, 8).level()).isEqualTo("half");
        assertThat(new Cell(DayOfWeek.MONDAY, 9, 6, 8).level()).isEqualTo("busy");
        assertThat(new Cell(DayOfWeek.MONDAY, 9, 8, 8).level()).isEqualTo("full");
        assertThat(new Cell(DayOfWeek.MONDAY, 9, 9, 8).percent()).as("a squeeze-in").isEqualTo(113);

        BusyHours busy = new BusyHours(List.of(9, 10), List.of(new BusyHours.Day(DayOfWeek.TUESDAY, List.of(
                new Cell(DayOfWeek.TUESDAY, 9, 8, 8), new Cell(DayOfWeek.TUESDAY, 10, 0, 8)))));
        assertThat(busy.busiest()).get().extracting(Cell::hour).isEqualTo(9);
        assertThat(busy.quietest()).get().extracting(Cell::hour).isEqualTo(10);
    }

    @Test
    void theCsvOpensInExcelWithArabicIntactAndNoFormulas() {
        MonthlyReport report = new MonthlyReport(1, "Dr. Demo, Zahle", new ReportMonth(YearMonth.of(2026, 10), BEIRUT),
                new AppointmentFigures(3, 1, 2, 4, 2, 0, 1, 1, 0, 0, 0, 0),
                List.of(new DoctorFigures(7, "=HYPERLINK(\"x\")", new AppointmentFigures(3, 1, 2, 4, 2, 0, 1, 1, 0, 0, 0, 0))),
                List.of(new MessageFigures("utility", 4, 1, new BigDecimal("0.0628"), 0)));

        String csv = ReportCsv.of(report, messageSource(), Locale.forLanguageTag("ar-LB"));

        assertThat(csv).startsWith("﻿").endsWith("\r\n");
        assertThat(csv.split("\r\n")).containsSubsequence(
                "﻿العيادة,\"Dr. Demo, Zahle\"",
                "الشهر,2026-10",
                "",
                "\"'=HYPERLINK(\"\"x\"\")\",3,1,2,4,2,0,1,1,0,0,0,0",
                "كل الأطباء,3,1,2,4,2,0,1,1,0,0,0,0",
                "تذكيرات وإشعارات (خدمية),4,1,0.0628,0");
        assertThat(ReportCsv.field("-1")).isEqualTo("'-1");
        assertThat(ReportCsv.field("plain")).isEqualTo("plain");
    }

    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
