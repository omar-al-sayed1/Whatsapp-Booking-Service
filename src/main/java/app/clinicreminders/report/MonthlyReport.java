package app.clinicreminders.report;

import java.math.BigDecimal;
import java.util.List;

/**
 * One clinic's month (SPEC section 5.4): what became of its appointments, by doctor and in all, and
 * the WhatsApp messages it sent with their estimated cost.
 *
 * @param doctors  by name: those with appointments that month, and those taking appointments
 * @param messages by pricing category; empty in a report for one doctor, as messages are the clinic's
 */
public record MonthlyReport(long clinicId, String clinic, ReportMonth month, AppointmentFigures total,
                            List<DoctorFigures> doctors, List<MessageFigures> messages) {

    /** One doctor's figures. */
    public record DoctorFigures(long doctorId, String doctor, AppointmentFigures figures) {
    }

    public long messagesSent() {
        return messages.stream().mapToLong(MessageFigures::sent).sum();
    }

    public long messagesFailed() {
        return messages.stream().mapToLong(MessageFigures::failed).sum();
    }

    public long messagesUnpriced() {
        return messages.stream().mapToLong(MessageFigures::unpriced).sum();
    }

    public BigDecimal estimatedUsd() {
        return messages.stream().map(MessageFigures::estimatedUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
