package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.staff.StaffPrincipal;
import app.clinicreminders.staff.StaffUser;
import app.clinicreminders.staff.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The Report page, its CSV, and the operator's cost tracking. Now is Wednesday 14 October 2026, 12:00 in Beirut. */
class ReportPagesIT extends DashboardTest {

    private static final Duration HALF_HOUR = Duration.ofMinutes(30);

    @Autowired
    StaffUserRepository users;

    private Clinic clinic;
    private Doctor khoury;
    private Doctor saab;
    private StaffPrincipal staff;

    @BeforeEach
    void aClinicWithAMonth() {
        clinic = data.connectedClinic("Dr. Demo", "pn-a");
        // Added in September, so the report goes back one month.
        jdbc.update("update clinic set created_at = '2026-09-10T08:00:00Z' where id = ?", clinic.getId());
        khoury = data.doctor(clinic, "Dr. Khoury", 30);
        saab = data.doctor(clinic, "Dr. Saab", 30);
        data.weekly(khoury, DayOfWeek.TUESDAY, "09:00", "11:00");
        Patient rana = data.patient(clinic, "Rana", "+96170123456", Language.AR);
        Appointment noShow = data.appointment(khoury, rana, Instant.parse("2026-10-06T06:00:00Z"), HALF_HOUR);
        jdbc.update("update appointment set status = 'NO_SHOW' where id = ?", noShow.getId());
        Appointment saved = data.appointment(saab, rana, Instant.parse("2026-10-07T06:00:00Z"), HALF_HOUR);
        jdbc.update("update appointment set status = 'CANCELLED', cancelled_by = 'PATIENT', cancelled_at = '2026-10-06T16:00:00Z' where id = ?",
                saved.getId());
        jdbc.update("""
                insert into message_log (clinic_id, direction, wa_message_id, kind, pricing_category, status, est_cost_usd, created_at)
                values (?, 'OUT', 'wamid.1', 'TEMPLATE', 'utility', 'READ', 0.0157, '2026-10-05T07:00:00Z')""", clinic.getId());
        staff = secretaryOf(clinic);
    }

    @Test
    void theMonthsReportWithBusyHoursAndMessages() {
        assertThat(get(staff, "/report")).hasStatusOk().bodyText()
                .contains("<h1>October 2026</h1>", "Slots saved", "Busy hours", "class=\"busy-grid\"", "Tuesday",
                        "By doctor", "Dr. Khoury", "Dr. Saab", "WhatsApp messages", "$0.02",
                        "Reminders and notices (utility)", "href=\"/report.csv?month=2026-10\"")
                .contains("href=\"/report?month=2026-09\"").as("no month after this one").doesNotContain("month=2026-11");
        assertThat(get(staff, "/report?month=2026-09")).bodyText().contains("<h1>September 2026</h1>", "No appointments this month.");
        assertThat(get(staff, "/report?month=2030-01")).bodyText().as("not ahead of now").contains("<h1>October 2026</h1>");
        assertThat(get(staff, "/report?month=2020-01")).bodyText().as("not before the clinic").contains("<h1>September 2026</h1>");
        assertThat(get(staff, "/report?month=soon")).bodyText().contains("<h1>October 2026</h1>");
    }

    @Test
    void theCsvIsADownloadExcelReads() throws Exception {
        var response = get(staff, "/report.csv?month=2026-10").exchange().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"report-2026-10.csv\"");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).startsWith("﻿Clinic,Dr. Demo\r\nMonth,2026-10\r\n")
                .contains("Dr. Khoury,1,0,0,0,0,1,0,0,0,0,0,0\r\n", "Dr. Saab,1,0,0,0,0,0,1,0,0,0,0,0\r\n",
                        "All doctors,2,0,0,0,0,1,1,0,0,0,0,0\r\n", "Reminders and notices (utility),1,0,0.0157,0\r\n");
    }

    @Test
    void aDoctorSeesOnlyTheirOwnMonthAndNotTheClinicsMessages() {
        StaffPrincipal khourysLogin = doctorLoginOf(khoury);

        assertThat(get(khourysLogin, "/report")).hasStatusOk().bodyText()
                .contains("<h1>October 2026</h1>", "href=\"/report\"")
                .doesNotContain("WhatsApp messages", "Dr. Saab", "By doctor", "$0.02");
        assertThat(get(khourysLogin, "/report.csv")).bodyText().contains("Dr. Khoury,1,").doesNotContain("Dr. Saab", "utility");
    }

    @Test
    void theReportIsInTheMenuForEveryClinicLogin() {
        assertThat(get(staff, "/")).bodyText().contains("class=\"nav-report\" href=\"/report\"", "class=\"menu-report\" href=\"/report\"");
        assertThat(get(doctorLoginOf(khoury), "/")).bodyText().contains("class=\"nav-report\" href=\"/report\"");
    }

    @Test
    void theOperatorSeesEveryClinicsMonthAndEachClinicsReport() {
        StaffPrincipal operator = operator();
        data.clinic("Another clinic");

        assertThat(get(operator, "/operator/report")).hasStatusOk().bodyText()
                .contains("Clinics this month", "October 2026", "Dr. Demo", "Another clinic", "$0.02",
                        "href=\"/operator/clinics/" + clinic.getId() + "/report?month=2026-10\"",
                        "No message rates are set");
        assertThat(get(operator, "/operator/clinics/{id}/report", clinic.getId())).hasStatusOk().bodyText()
                .contains("<h1>October 2026</h1>", "Dr. Demo", "Busy hours", "WhatsApp messages",
                        "href=\"/operator/clinics/" + clinic.getId() + "/report.csv?month=2026-10\"");
        assertThat(get(operator, "/operator/clinics/{id}/report.csv", clinic.getId())).hasStatusOk()
                .bodyText().contains("Dr. Khoury,1,");
        assertThat(get(staff, "/operator/report")).hasStatus(403);
        assertThat(get(operator, "/report")).as("an operator has no clinic of their own").hasStatus4xxClientError();
    }

    @Test
    void theOperatorKeepsMetasRates() {
        StaffPrincipal operator = operator();

        assertThat(post(operator, "/operator/rates").param("market", "Rest of Middle East").param("category", "Utility")
                .param("usdPerMessage", "0.0157").param("validFrom", "2026-10-01"))
                .hasRedirectedUrl("/operator/rates").flash().containsEntry("notice", "notice.saved");
        assertThat(get(operator, "/operator/rates")).bodyText().contains("Rest of Middle East", "utility", "0.0157", "2026-10-01");
        assertThat(jdbc.queryForObject("select count(*) from message_rate", Integer.class)).isEqualTo(1);

        for (String[] bad : new String[][] {{"Mars", "utility", "0.01"}, {"Rest of Middle East", "utility", "1.50"},
                {"Rest of Middle East", "utility", "-0.01"}, {"Rest of Middle East", "u t", "0.01"},
                {"Rest of Middle East", "utility", "lots"}, {"Rest of Middle East", "utility", "0.0157"}}) {
            assertThat(post(operator, "/operator/rates").param("market", bad[0]).param("category", bad[1])
                    .param("usdPerMessage", bad[2]).param("validFrom", "2026-10-01"))
                    .as(String.join(" ", bad)).flash().containsEntry("error", "error.rate");
        }

        long id = jdbc.queryForObject("select id from message_rate", Long.class);
        assertThat(post(operator, "/operator/rates/{id}/delete", id)).hasRedirectedUrl("/operator/rates");
        assertThat(jdbc.queryForObject("select count(*) from message_rate", Integer.class)).isZero();
        assertThat(post(staff, "/operator/rates/{id}/delete", id)).hasStatus(403);
    }

    private StaffPrincipal operator() {
        return StaffPrincipal.of(users.save(StaffUser.operator("op.reports", "{noop}x", Language.EN)));
    }
}
