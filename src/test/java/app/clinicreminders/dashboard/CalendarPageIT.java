package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.scheduling.Doctor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * "Add to my calendar": a public page per appointment, keyed by a random token. Now is Wednesday
 * 14 October 2026, 12:00 in Beirut; Rana (Arabic) is booked on Thursday at 10:30.
 */
class CalendarPageIT extends DashboardTest {

    private static final Instant THU_1030 = Instant.parse("2026-10-15T07:30:00Z");

    @Autowired
    AppointmentRepository appointments;

    @Autowired
    ClinicRepository clinics;

    private Clinic clinic;
    private Doctor khoury;
    private Appointment ranas;

    @BeforeEach
    void anAppointment() {
        clinic = data.connectedClinic("Dr. Demo", "pn-a");
        clinic.changeContactDetails("Main street, Zahle", "Mon-Sat 9-1");
        clinics.save(clinic);
        khoury = data.doctor(clinic, "Dr. Khoury", 30);
        Patient rana = data.patient(clinic, "Rana Haddad", "+96170123456", Language.AR);
        ranas = reloaded(data.appointment(khoury, rana, THU_1030, Duration.ofMinutes(30)));
    }

    @Test
    void thePageNeedsNoLoginAndIsInThePatientsLanguage() {
        assertThat(mvc.get().uri("/c/{token}", ranas.getCalendarToken())).hasStatusOk()
                .hasHeader("X-Robots-Tag", "noindex, nofollow")
                .bodyText()
                .contains("lang=\"ar\" dir=\"rtl\"", "Dr. Demo", "Dr. Khoury", "الخميس 15 تشرين الأول", "10:30 ص",
                        "Main street, Zahle", "أضِف إلى تقويمي", "href=\"/c/" + ranas.getCalendarToken() + "/calendar.ics\"",
                        "https://calendar.google.com/calendar/render?action=TEMPLATE")
                .as("nothing about the patient").doesNotContain("Rana", "+96170123456");

        Patient karim = data.patient(clinic, "Karim", "+96171222333", Language.EN);
        Appointment karims = reloaded(data.appointment(khoury, karim, THU_1030.plusSeconds(1800), Duration.ofMinutes(30)));
        assertThat(mvc.get().uri("/c/{token}", karims.getCalendarToken())).bodyText()
                .contains("lang=\"en\" dir=\"ltr\"", "Thursday 15 October", "11:00 AM", "Add to my calendar");
        assertThat(karims.getCalendarToken()).isNotEqualTo(ranas.getCalendarToken());
    }

    @Test
    void theCalendarFileIsTheAppointmentInUtc() {
        assertThat(mvc.get().uri("/c/{token}/calendar.ics", ranas.getCalendarToken())).hasStatusOk()
                .hasContentType("text/calendar;charset=UTF-8")
                .bodyText()
                .contains("DTSTART:20261015T073000Z", "DTEND:20261015T080000Z", "SUMMARY:Dr. Khoury · Dr. Demo",
                        "LOCATION:Main street\\, Zahle", "STATUS:CONFIRMED", "TRIGGER:-PT1H")
                .doesNotContain("Rana");
    }

    @Test
    void anUnknownOrMalformedLinkIsNotFound() {
        assertThat(mvc.get().uri("/c/{token}", "6f1c2a0e-1d2b-4c3d-9e8f-0a1b2c3d4e5f")).hasStatus(404);
        assertThat(mvc.get().uri("/c/{token}", "123")).hasStatus(404);
        assertThat(mvc.get().uri("/c/{token}/calendar.ics", "nope")).hasStatus(404);
    }

    @Test
    void aCancelledAppointmentSaysSoAndOffersNothingToAdd() {
        jdbc.update("update appointment set status = 'CANCELLED', cancelled_at = now(), cancelled_by = 'STAFF' where id = ?",
                ranas.getId());

        assertThat(mvc.get().uri("/c/{token}", ranas.getCalendarToken())).bodyText()
                .contains("أُلغي هذا الموعد.").doesNotContain("calendar.ics");
        assertThat(mvc.get().uri("/c/{token}/calendar.ics", ranas.getCalendarToken())).bodyText()
                .contains("STATUS:CANCELLED").doesNotContain("VALARM");
    }

    @Test
    void theSecretaryCanCopyTheLinkForAPatientBookedByPhone() {
        assertThat(get(secretaryOf(clinic), "/appointments/{id}", ranas.getId())).bodyText()
                .contains("Calendar link", "data-copy=\"https://reminders.test/c/" + ranas.getCalendarToken() + "\"");
    }

    private Appointment reloaded(Appointment appointment) {
        return appointments.findByIdAndClinicId(appointment.getId(), clinic.getId()).orElseThrow();
    }
}
