package app.clinicreminders.patient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.clinicreminders.common.Language;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operator's view of every clinic's patients, with filters. Deliberately cross-tenant (like a
 * {@link app.clinicreminders.common.CrossTenant} query): only the operator pages use it, and those
 * are for operators only.
 *
 * <p>"Upcoming" is a booked or confirmed appointment still to come; "last appointment" is the latest
 * past one that wasn't cancelled.
 */
@Component
@Transactional(readOnly = true)
public class PatientDirectory {

    public static final int PAGE_SIZE = 25;

    public enum Booking { ANY, UPCOMING, NONE }

    public enum LastAppointment { ANY, LAST_30_DAYS, LAST_90_DAYS, OVER_6_MONTHS, NEVER }

    public enum Messages { ANY, REACHABLE, OPTED_OUT }

    public enum Sort { NAME, NEWEST, LAST_APPOINTMENT, NEXT_APPOINTMENT }

    /**
     * @param query  name, or any part of the phone number
     * @param page   from 1
     */
    public record Filter(String query, @Nullable Long clinicId, @Nullable Language language, Booking booking,
                         LastAppointment lastAppointment, Messages messages, Sort sort, int page) {
    }

    public record Row(long id, long clinicId, String name, String phoneE164, Language language, boolean optedOut,
                      Instant createdAt, @Nullable Instant nextAppointment, @Nullable Instant lastAppointment,
                      long appointments) {
    }

    /** @param total how many patients match, over all pages */
    public record Page(List<Row> rows, long total, int page, int pages) {
    }

    private static final String OPEN = "(app.clinicreminders.appointment.AppointmentStatus.BOOKED, "
            + "app.clinicreminders.appointment.AppointmentStatus.CONFIRMED)";
    private static final String NOT_CANCELLED = "a.status <> app.clinicreminders.appointment.AppointmentStatus.CANCELLED";
    private static final String OF_PATIENT = "a.patientId = p.id and a.clinicId = p.clinicId";
    private static final String UPCOMING = "exists (select 1 from Appointment a where " + OF_PATIENT
            + " and a.startsAt > :now and a.status in " + OPEN + ")";
    private static final String PAST = "from Appointment a where " + OF_PATIENT + " and a.startsAt <= :now and " + NOT_CANCELLED;

    private final EntityManager entityManager;

    PatientDirectory(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Page search(Filter filter, Instant now) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        String where = where(filter, now, parameters);

        TypedQuery<Long> count = entityManager.createQuery("select count(p) from Patient p where " + where, Long.class);
        parameters.forEach(count::setParameter);
        long total = count.getSingleResult();
        int pages = (int) Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.clamp(filter.page(), 1, pages);

        TypedQuery<Tuple> query = entityManager.createQuery("""
                select p.id as id, p.clinicId as clinicId, p.name as name, p.phoneE164 as phone, p.language as language,
                       p.optedOut as optedOut, p.createdAt as createdAt,
                       (select min(a.startsAt) from Appointment a where %s and a.startsAt > :now and a.status in %s) as nextAt,
                       (select max(a.startsAt) %s) as lastAt,
                       (select count(a) from Appointment a where %s and %s) as appointments
                from Patient p
                where %s
                order by %s""".formatted(OF_PATIENT, OPEN, PAST, OF_PATIENT, NOT_CANCELLED, where, order(filter.sort())),
                Tuple.class);
        parameters.forEach(query::setParameter);
        query.setParameter("now", now);
        query.setFirstResult((page - 1) * PAGE_SIZE);
        query.setMaxResults(PAGE_SIZE);
        List<Row> rows = query.getResultList().stream()
                .map(t -> new Row(t.get("id", Long.class), t.get("clinicId", Long.class), t.get("name", String.class),
                        t.get("phone", String.class), t.get("language", Language.class), t.get("optedOut", Boolean.class),
                        t.get("createdAt", Instant.class), t.get("nextAt", Instant.class), t.get("lastAt", Instant.class),
                        t.get("appointments", Long.class)))
                .toList();
        return new Page(rows, total, page, pages);
    }

    private static String where(Filter filter, Instant now, Map<String, Object> parameters) {
        List<String> conditions = new ArrayList<>();
        conditions.add("1 = 1");
        String name = filter.query().strip();
        if (!name.isEmpty()) {
            String digits = PatientService.searchDigits(name);
            parameters.put("name", name.toLowerCase());
            if (digits.isEmpty()) {
                conditions.add("lower(p.name) like concat('%', :name, '%')");
            } else {
                parameters.put("digits", digits);
                conditions.add("(lower(p.name) like concat('%', :name, '%') or p.phoneE164 like concat('%', :digits, '%'))");
            }
        }
        if (filter.clinicId() != null) {
            parameters.put("clinicId", filter.clinicId());
            conditions.add("p.clinicId = :clinicId");
        }
        if (filter.language() != null) {
            parameters.put("language", filter.language());
            conditions.add("p.language = :language");
        }
        switch (filter.messages()) {
            case REACHABLE -> conditions.add("p.optedOut = false");
            case OPTED_OUT -> conditions.add("p.optedOut = true");
            case ANY -> { }
        }
        boolean usesNow = false;
        switch (filter.booking()) {
            case UPCOMING -> conditions.add(UPCOMING);
            case NONE -> conditions.add("not " + UPCOMING);
            case ANY -> { }
        }
        usesNow |= filter.booking() != Booking.ANY;
        switch (filter.lastAppointment()) {
            case LAST_30_DAYS -> conditions.add(pastSince(parameters, now.minus(Duration.ofDays(30))));
            case LAST_90_DAYS -> conditions.add(pastSince(parameters, now.minus(Duration.ofDays(90))));
            case OVER_6_MONTHS -> conditions.add("exists (select 1 " + PAST + ") and not "
                    + pastSince(parameters, now.minus(Duration.ofDays(182))));
            case NEVER -> conditions.add("not exists (select 1 " + PAST + ")");
            case ANY -> { }
        }
        usesNow |= filter.lastAppointment() != LastAppointment.ANY;
        if (usesNow) {
            parameters.put("now", now);
        }
        return String.join(" and ", conditions);
    }

    private static String pastSince(Map<String, Object> parameters, Instant since) {
        parameters.put("since", since);
        return "exists (select 1 " + PAST + " and a.startsAt >= :since)";
    }

    private static String order(Sort sort) {
        return switch (sort) {
            case NAME -> "lower(p.name), p.id";
            case NEWEST -> "p.createdAt desc, p.id desc";
            case LAST_APPOINTMENT -> "lastAt desc nulls last, lower(p.name), p.id";
            case NEXT_APPOINTMENT -> "nextAt asc nulls last, lower(p.name), p.id";
        };
    }
}
