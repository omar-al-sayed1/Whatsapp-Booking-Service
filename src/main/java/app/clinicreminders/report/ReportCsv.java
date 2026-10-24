package app.clinicreminders.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import app.clinicreminders.report.MonthlyReport.DoctorFigures;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;

/**
 * The monthly report as CSV, for Excel: a byte order mark (without it Excel shows Arabic as
 * gibberish), comma-separated, CRLF lines (RFC 4180). The clinic and month, a row per doctor and
 * their total, then the messages by category. Headings are in the reader's language; numbers use
 * Western digits and a dot.
 */
public final class ReportCsv {

    /** UTF-8 byte order mark. */
    private static final String BOM = "﻿";

    private ReportCsv() {
    }

    public static String of(MonthlyReport report, MessageSource messages, Locale locale) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of(text(messages, locale, "report.csv.clinic"), report.clinic()));
        rows.add(List.of(text(messages, locale, "report.csv.month"), report.month().month().toString()));
        rows.add(List.of());
        rows.add(headings(messages, locale, "report.doctor", "report.col.appointments", "report.col.on-whatsapp",
                "report.col.confirmed", "report.col.reminders", "report.col.arrived", "report.col.no-shows",
                "report.col.slots-saved", "report.col.refilled", "report.col.late", "report.col.by-clinic",
                "report.col.upcoming", "report.col.unmarked"));
        for (DoctorFigures doctor : report.doctors()) {
            rows.add(figures(doctor.doctor(), doctor.figures()));
        }
        rows.add(figures(text(messages, locale, "report.all-doctors"), report.total()));
        if (!report.messages().isEmpty()) {
            rows.add(List.of());
            rows.add(headings(messages, locale, "report.col.category", "report.col.sent", "report.col.failed",
                    "report.col.cost", "report.col.unpriced"));
            for (MessageFigures category : report.messages()) {
                rows.add(List.of(categoryLabel(messages, locale, category.category()), String.valueOf(category.sent()),
                        String.valueOf(category.failed()), usd(category.estimatedUsd()), String.valueOf(category.unpriced())));
            }
            rows.add(List.of(text(messages, locale, "report.total"), String.valueOf(report.messagesSent()),
                    String.valueOf(report.messagesFailed()), usd(report.estimatedUsd()),
                    String.valueOf(report.messagesUnpriced())));
        }
        return BOM + rows.stream()
                .map(row -> row.stream().map(ReportCsv::field).collect(Collectors.joining(",")))
                .collect(Collectors.joining("\r\n", "", "\r\n"));
    }

    /** A pricing category as the report names it; one the report doesn't know keeps Meta's name. */
    public static String categoryLabel(MessageSource messages, Locale locale, @Nullable String category) {
        String key = "report.category." + (category == null ? "none" : category);
        return messages.getMessage(key, null, category == null ? "" : category, locale);
    }

    /** Dollars with four decimals: one message costs a few cents. */
    static String usd(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Quoted when it holds a comma, quote or line break. A cell starting like a formula gets a
     * leading apostrophe, so a doctor's name like "=HYPERLINK(...)" stays text in Excel.
     */
    static String field(String value) {
        String safe = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        return safe.matches("(?s).*[\",\r\n].*") ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }

    private static List<String> figures(String label, AppointmentFigures f) {
        return List.of(label, String.valueOf(f.appointments()), String.valueOf(f.bookedOnWhatsApp()),
                String.valueOf(f.confirmed()), String.valueOf(f.remindersSent()), String.valueOf(f.arrived()),
                String.valueOf(f.noShows()), String.valueOf(f.slotsSaved()), String.valueOf(f.refilled()),
                String.valueOf(f.lateCancellations()), String.valueOf(f.cancelledByClinic()), String.valueOf(f.upcoming()),
                String.valueOf(f.unmarked()));
    }

    private static List<String> headings(MessageSource messages, Locale locale, String... keys) {
        List<String> headings = new ArrayList<>();
        for (String key : keys) {
            headings.add(text(messages, locale, key));
        }
        return headings;
    }

    private static String text(MessageSource messages, Locale locale, String key) {
        return messages.getMessage(key, null, locale);
    }
}
