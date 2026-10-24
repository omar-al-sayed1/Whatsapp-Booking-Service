package app.clinicreminders.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.report.BusyHours.Cell;
import app.clinicreminders.report.MonthlyReport.DoctorFigures;
import app.clinicreminders.scheduling.Doctor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * October 2026 at one clinic, in Beirut, where clocks go back on Sunday 25 October. Now is Wednesday
 * 14 October, 12:00. Dr. Khoury works Monday to Saturday 09:00-11:00 in 30-minute slots; Dr. Saab
 * has no weekly hours, only two squeeze-ins at the very edges of the month.
 */
class ReportsIT extends IntegrationTest {

    private static final ZoneId BEIRUT = ZoneId.of("Asia/Beirut");
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final Duration HALF_HOUR = Duration.ofMinutes(30);

    @Autowired
    Reports reports;

    private Clinic clinic;
    private Doctor khoury;
    private Doctor saab;
    private Patient rana;

    @BeforeEach
    void october() {
        clinic = data.clinic("A");
        khoury = data.doctor(clinic, "Dr. Khoury", 30);
        saab = data.doctor(clinic, "Dr. Saab", 30);
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day != DayOfWeek.SUNDAY) {
                data.weekly(khoury, day, "09:00", "11:00");
            }
        }
        rana = data.patient(clinic, "Rana", "+96170123456", Language.AR);

        Appointment arrived = at(khoury, "2026-10-05T09:00");
        update("status = 'ARRIVED', source = 'WHATSAPP', confirmed_at = ?", arrived, local("2026-10-04T13:00"));
        sentReminders(arrived, 2);
        Appointment noShow = at(khoury, "2026-10-06T09:00");
        update("status = 'NO_SHOW'", noShow);
        sentReminders(noShow, 1);
        jdbc.update("insert into reminder (clinic_id, appointment_id, kind, due_at, status) values (?, ?, 'H2', now(), 'FAILED')",
                clinic.getId(), noShow.getId());
        // Cancelled on WhatsApp the evening before, and the slot booked again by someone else.
        Appointment saved = at(khoury, "2026-10-07T09:00");
        update("status = 'CANCELLED', cancelled_by = 'PATIENT', cancelled_at = ?", saved, local("2026-10-06T19:00"));
        at(khoury, "2026-10-07T09:00");
        Appointment late = at(khoury, "2026-10-08T09:00");
        update("status = 'CANCELLED', cancelled_by = 'PATIENT', cancelled_at = ?", late, local("2026-10-08T08:00"));
        Appointment byClinic = at(khoury, "2026-10-09T09:00");
        update("status = 'CANCELLED', cancelled_by = 'STAFF', cancelled_at = ?", byClinic, local("2026-10-08T10:00"));
        at(khoury, "2026-10-20T09:00");

        // Midnight on the 1st is 21:00 UTC (summer time), midnight on 1 November 22:00 UTC (winter time).
        at(saab, "2026-10-01T00:30");
        sentReminders(at(saab, "2026-10-31T23:30"), 1);
        at(saab, "2026-11-01T00:30");
        at(saab, "2026-09-30T23:30");

        message("utility", "SENT", "0.0157", "2026-10-05T08:00");
        message("utility", "DELIVERED", "0.0157", "2026-10-06T08:00");
        message("utility", "FAILED", null, "2026-10-06T08:05");
        message("service", "READ", null, "2026-10-10T10:00");
        message("service", "READ", "0.0040", "2026-10-31T23:00");
        message("service", "READ", "0.0040", "2026-09-30T23:59");
        jdbc.update("""
                insert into message_log (clinic_id, direction, wa_message_id, kind, created_at)
                values (?, 'IN', 'wamid.IN-1', 'TEXT', ?)""", clinic.getId(), local("2026-10-10T10:01").atOffset(ZoneOffset.UTC));

        Clinic other = data.clinic("B");
        Doctor theirs = data.doctor(other, 30);
        Patient theirPatient = data.patient(other, "Nour", "+96171000000", Language.AR);
        data.appointment(theirs, theirPatient, local("2026-10-05T10:00"), HALF_HOUR);
        jdbc.update("""
                insert into message_log (clinic_id, direction, wa_message_id, kind, pricing_category, status, est_cost_usd, created_at)
                values (?, 'OUT', 'wamid.B-1', 'TEMPLATE', 'utility', 'SENT', 0.5, ?)""", other.getId(),
                local("2026-10-05T08:00").atOffset(ZoneOffset.UTC));
    }

    @Test
    void whatBecameOfTheMonthsAppointments() {
        MonthlyReport report = reports.monthly(clinic.getId(), OCTOBER, null, clock.instant());

        assertThat(report.clinic()).isEqualTo("A");
        assertThat(report.doctors()).extracting(DoctorFigures::doctor).containsExactly("Dr. Khoury", "Dr. Saab");
        AppointmentFigures khourys = report.doctors().getFirst().figures();
        assertThat(khourys).isEqualTo(new AppointmentFigures(7, 1, 1, 3, 1, 1, 1, 1, 1, 1, 1, 1));
        assertThat(report.doctors().get(1).figures()).as("from midnight on the 1st to midnight on 1 November, local")
                .isEqualTo(new AppointmentFigures(2, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 1));
        assertThat(report.total().appointments()).isEqualTo(9);
        assertThat(report.total().noShowPercent()).isEqualTo(50);
    }

    @Test
    void messagesByCategoryWithTheirEstimatedCost() {
        MonthlyReport report = reports.monthly(clinic.getId(), OCTOBER, null, clock.instant());

        assertThat(report.messages()).extracting(MessageFigures::category, MessageFigures::sent, MessageFigures::failed,
                        MessageFigures::unpriced)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("service", 2L, 0L, 1L),
                        org.assertj.core.groups.Tuple.tuple("utility", 2L, 1L, 0L));
        assertThat(report.messagesSent()).isEqualTo(4);
        assertThat(report.estimatedUsd()).isEqualByComparingTo("0.0354");
    }

    @Test
    void aDoctorsOwnReportHasOnlyTheirAppointmentsAndNoMessages() {
        MonthlyReport report = reports.monthly(clinic.getId(), OCTOBER, saab.getId(), clock.instant());

        assertThat(report.doctors()).extracting(DoctorFigures::doctor).containsExactly("Dr. Saab");
        assertThat(report.total().appointments()).isEqualTo(2);
        assertThat(report.messages()).isEmpty();
    }

    @Test
    void anotherClinicsMonthIsItsOwn() {
        MonthlyReport theirs = reports.monthly(jdbc.queryForObject("select id from clinic where name = 'B'", Long.class),
                OCTOBER, null, clock.instant());

        assertThat(theirs.total().appointments()).isEqualTo(1);
        assertThat(theirs.estimatedUsd()).isEqualByComparingTo("0.5");
        assertThat(reports.allClinics(OCTOBER, clock.instant())).extracting(MonthlyReport::clinic).containsExactly("A", "B");
    }

    @Test
    void busyHoursSetBookingsAgainstTheSlotsOffered() {
        BusyHours busy = reports.busyHours(reports.monthly(clinic.getId(), OCTOBER, null, clock.instant()));

        assertThat(busy.hours()).as("every hour from the first squeeze-in to the last").startsWith(0).endsWith(23).hasSize(24);
        assertThat(busy.days()).extracting(BusyHours.Day::day)
                .containsExactly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                        DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);
        // Four Tuesdays in October, two slots in the 9 o'clock hour: the no-show and the one still to come are booked.
        assertThat(cell(busy, DayOfWeek.TUESDAY, 9)).extracting(Cell::booked, Cell::open).containsExactly(2L, 8L);
        assertThat(cell(busy, DayOfWeek.WEDNESDAY, 9).booked()).as("the cancelled one doesn't count, its refill does").isEqualTo(1);
        assertThat(cell(busy, DayOfWeek.FRIDAY, 10).level()).isEqualTo("empty");
        assertThat(cell(busy, DayOfWeek.SATURDAY, 23).level()).isEqualTo("outside");
        assertThat(cell(busy, DayOfWeek.SATURDAY, 15).level()).isEqualTo("closed");
        assertThat(busy.busiest()).get().extracting(Cell::day, Cell::hour).containsExactly(DayOfWeek.TUESDAY, 9);
    }

    private static Cell cell(BusyHours busy, DayOfWeek day, int hour) {
        return busy.days().stream().filter(d -> d.day() == day).findFirst().orElseThrow()
                .cells().stream().filter(c -> c.hour() == hour).findFirst().orElseThrow();
    }

    private Appointment at(Doctor doctor, String localStart) {
        return data.appointment(doctor, rana, local(localStart), HALF_HOUR);
    }

    private void update(String set, Appointment appointment, Object... values) {
        Object[] arguments = new Object[values.length + 1];
        for (int i = 0; i < values.length; i++) {
            arguments[i] = values[i] instanceof Instant instant ? instant.atOffset(ZoneOffset.UTC) : values[i];
        }
        arguments[values.length] = appointment.getId();
        jdbc.update("update appointment set " + set + " where id = ?", arguments);
    }

    private void sentReminders(Appointment appointment, int count) {
        for (int i = 0; i < count; i++) {
            jdbc.update("""
                    insert into reminder (clinic_id, appointment_id, kind, due_at, status, sent_at)
                    values (?, ?, ?, now(), 'SENT', now())""", clinic.getId(), appointment.getId(), i == 0 ? "H24" : "H2");
        }
    }

    private void message(String category, String status, String cost, String localTime) {
        jdbc.update("""
                insert into message_log (clinic_id, direction, wa_message_id, kind, pricing_category, status, est_cost_usd, created_at)
                values (?, 'OUT', ?, 'TEMPLATE', ?, ?, ?::numeric, ?)""", clinic.getId(), "wamid." + localTime + status,
                category, status, cost, local(localTime).atOffset(ZoneOffset.UTC));
    }

    private static Instant local(String dateTime) {
        return LocalDateTime.parse(dateTime).atZone(BEIRUT).toInstant();
    }
}
