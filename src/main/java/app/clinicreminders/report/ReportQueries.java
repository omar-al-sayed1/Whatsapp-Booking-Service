package app.clinicreminders.report;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The monthly report's counts, in SQL: one pass over a clinic's appointments of the month, one over
 * their sent reminders and one over its outbound messages. Every query takes the clinic id (the
 * multi-tenancy rule; this is not a Spring Data repository, so the convention test can't check it).
 */
@Component
class ReportQueries {

    /** "Slots saved" are cancellations at least this far ahead (SPEC section 5.4). */
    static final String SAVED_AHEAD = "2 hours";

    private static final String PATIENT_CANCELLED = "a.status = 'CANCELLED' and a.cancelled_by = 'PATIENT'";
    private static final String SAVED = PATIENT_CANCELLED + " and a.cancelled_at <= a.starts_at - interval '" + SAVED_AHEAD + "'";

    private final JdbcClient jdbc;

    ReportQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * What became of the appointments starting in the month, by doctor (reminders not counted yet).
     * A saved slot is refilled when the doctor has an active appointment at the same start.
     */
    Map<Long, AppointmentFigures> appointmentsByDoctor(long clinicId, ReportMonth month, Instant now) {
        Map<Long, AppointmentFigures> figures = new HashMap<>();
        jdbc.sql("""
                        select a.doctor_id,
                               count(*) as appointments,
                               count(*) filter (where a.source = 'WHATSAPP') as booked_on_whatsapp,
                               count(*) filter (where a.confirmed_at is not null) as confirmed,
                               count(*) filter (where a.status = 'ARRIVED') as arrived,
                               count(*) filter (where a.status = 'NO_SHOW') as no_shows,
                               count(*) filter (where %1$s) as slots_saved,
                               count(*) filter (where %1$s and refill.id is not null) as refilled,
                               count(*) filter (where %2$s and not (%1$s)) as late_cancellations,
                               count(*) filter (where a.status = 'CANCELLED'
                                                  and a.cancelled_by is distinct from 'PATIENT') as cancelled_by_clinic,
                               count(*) filter (where a.status in ('BOOKED', 'CONFIRMED') and a.starts_at > :now) as upcoming,
                               count(*) filter (where a.status in ('BOOKED', 'CONFIRMED') and a.starts_at <= :now) as unmarked
                        from appointment a
                        left join appointment refill
                               on a.status = 'CANCELLED' and refill.clinic_id = a.clinic_id and refill.doctor_id = a.doctor_id
                              and refill.starts_at = a.starts_at and refill.status <> 'CANCELLED'
                        where a.clinic_id = :clinicId and a.starts_at >= :from and a.starts_at < :to
                        group by a.doctor_id""".formatted(SAVED, PATIENT_CANCELLED))
                .param("clinicId", clinicId)
                .param("from", utc(month.from()))
                .param("to", utc(month.to()))
                .param("now", utc(now))
                .query((ResultSet rs) -> {
                    figures.put(rs.getLong("doctor_id"), new AppointmentFigures(rs.getLong("appointments"),
                            rs.getLong("booked_on_whatsapp"), rs.getLong("confirmed"), 0, rs.getLong("arrived"),
                            rs.getLong("no_shows"), rs.getLong("slots_saved"), rs.getLong("refilled"),
                            rs.getLong("late_cancellations"), rs.getLong("cancelled_by_clinic"), rs.getLong("upcoming"),
                            rs.getLong("unmarked")));
                });
        return figures;
    }

    /** Reminders sent for the appointments starting in the month, by doctor. */
    Map<Long, Long> remindersSentByDoctor(long clinicId, ReportMonth month) {
        Map<Long, Long> sent = new HashMap<>();
        jdbc.sql("""
                        select a.doctor_id, count(*) as sent
                        from reminder r
                        join appointment a on a.id = r.appointment_id and a.clinic_id = r.clinic_id
                        where r.clinic_id = :clinicId and r.status = 'SENT'
                          and a.starts_at >= :from and a.starts_at < :to
                        group by a.doctor_id""")
                .param("clinicId", clinicId)
                .param("from", utc(month.from()))
                .param("to", utc(month.to()))
                .query((ResultSet rs) -> {
                    sent.put(rs.getLong("doctor_id"), rs.getLong("sent"));
                });
        return sent;
    }

    /** The messages the clinic sent in the month, by pricing category; failed sends have no estimate and cost nothing. */
    List<MessageFigures> messages(long clinicId, ReportMonth month) {
        return jdbc.sql("""
                        select pricing_category,
                               count(*) filter (where status is distinct from 'FAILED') as sent,
                               count(*) filter (where status = 'FAILED') as failed,
                               coalesce(sum(est_cost_usd) filter (where status is distinct from 'FAILED'), 0) as estimated_usd,
                               count(*) filter (where status is distinct from 'FAILED' and est_cost_usd is null) as unpriced
                        from message_log
                        where clinic_id = :clinicId and direction = 'OUT' and created_at >= :from and created_at < :to
                        group by pricing_category
                        order by pricing_category nulls last""")
                .param("clinicId", clinicId)
                .param("from", utc(month.from()))
                .param("to", utc(month.to()))
                .query((rs, row) -> messageFigures(rs))
                .list();
    }

    private static MessageFigures messageFigures(ResultSet rs) throws SQLException {
        BigDecimal estimated = rs.getBigDecimal("estimated_usd");
        return new MessageFigures(rs.getString("pricing_category"), rs.getLong("sent"), rs.getLong("failed"),
                estimated == null ? BigDecimal.ZERO : estimated, rs.getLong("unpriced"));
    }

    /** The JDBC driver takes an offset date-time, not an Instant. */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
