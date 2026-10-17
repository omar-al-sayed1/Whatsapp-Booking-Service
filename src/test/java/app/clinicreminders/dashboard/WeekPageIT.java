package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentStatus;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.SlotService;
import app.clinicreminders.staff.StaffPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WeekPageIT extends DashboardTest {

    @Autowired
    SlotService slots;

    @Autowired
    AppointmentRepository appointments;

    private Clinic clinic;
    private Doctor doctor;
    private StaffPrincipal staff;

    @BeforeEach
    void doctorWorkingWednesdayMornings() {
        clinic = data.connectedClinic("A", "pn-a");
        doctor = data.doctor(clinic, "Dr. Khoury", 30);
        data.weekly(doctor, DayOfWeek.WEDNESDAY, "09:00", "10:00");
        staff = secretaryOf(clinic);
    }

    @Test
    void showsTheDoctorsSlotsWithAppointmentsInPlace() {
        // Wednesday 21 October 09:30 in Beirut (UTC+3).
        data.appointment(doctor, data.patient(clinic, "Rana", "+96170123456", Language.AR),
                Instant.parse("2026-10-21T06:30:00Z"), Duration.ofMinutes(30));

        assertThat(get(staff, "/week?doctorId={id}&start=2026-10-21", doctor.getId())).hasStatusOk().bodyText()
                .contains("Mon 19 Oct", "Wed 21 Oct", "Rana")
                .contains("/appointments/new?doctorId=" + doctor.getId() + "&amp;date=2026-10-21&amp;time=09:00")
                .doesNotContain("date=2026-10-21&amp;time=09:30");
    }

    @Test
    void pastSlotsCannotBeBooked() {
        // This week: Wednesday 14 October 09:00 has passed (it is 12:00).
        assertThat(get(staff, "/week?doctorId={id}", doctor.getId())).bodyText()
                .doesNotContain("date=2026-10-14&amp;time=09:00");
    }

    @Test
    void anotherClinicsDoctorIsNeverShown() {
        Clinic other = data.clinic("B");
        Doctor theirs = data.doctor(other, "Dr. Elsewhere", 30);

        assertThat(get(staff, "/week?doctorId={id}", theirs.getId())).hasStatusOk().bodyText()
                .contains("Dr. Khoury")
                .doesNotContain("Dr. Elsewhere");
    }

    @Test
    void onAPhoneTheDayStripJumpsToEachDayAndThisWeekLandsOnToday() {
        assertThat(get(staff, "/week?doctorId={id}", doctor.getId())).bodyText()
                .contains("class=\"day-strip\"", "href=\"#day-2026-10-12\"", "href=\"#day-2026-10-18\"",
                        "id=\"day-2026-10-14\"", "data-focus-day=\"2026-10-14\"", ">Wed<");
        assertThat(get(staff, "/week?doctorId={id}&start=2026-10-21", doctor.getId())).bodyText()
                .as("another week starts at the top").doesNotContain("data-focus-day");
    }

    // --- Opening a closed day ---

    @Test
    void aClosedDayStillToComeCanBeOpenedForThatDate() {
        // Thursday 22 October has no hours.
        assertThat(get(staff, "/week?doctorId={id}&start=2026-10-21", doctor.getId())).bodyText()
                .contains("Open this day", "value=\"2026-10-22\"", "value=\"09:00\"", "value=\"10:00\"");

        assertThat(post(staff, "/week/open-day").param("doctorId", doctor.getId().toString()).param("date", "2026-10-22")
                .param("start", "16:00").param("end", "17:00"))
                .hasRedirectedUrl("/week?doctorId=" + doctor.getId() + "&start=2026-10-19")
                .flash().containsEntry("notice", "notice.day-opened").containsEntry("focusDay", "2026-10-22");
        assertThat(slots.freeSlots(clinic.getId(), doctor.getId(), LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 22)))
                .hasSize(2);
        assertThat(slots.freeSlots(clinic.getId(), doctor.getId(), LocalDate.of(2026, 10, 29), LocalDate.of(2026, 10, 29)))
                .as("only that date").isEmpty();
    }

    @Test
    void pastDaysBadHoursAndOtherClinicsCannotBeOpened() {
        assertThat(post(staff, "/week/open-day").param("doctorId", doctor.getId().toString()).param("date", "2026-10-13")
                .param("start", "09:00").param("end", "10:00")).flash().containsEntry("error", "error.not-allowed");
        assertThat(post(staff, "/week/open-day").param("doctorId", doctor.getId().toString()).param("date", "2026-10-22")
                .param("start", "10:00").param("end", "09:00")).flash().containsEntry("error", "error.hours");
        Doctor theirs = data.doctor(data.clinic("B"), "Dr. Elsewhere", 30);
        assertThat(post(staff, "/week/open-day").param("doctorId", theirs.getId().toString()).param("date", "2026-10-22")
                .param("start", "09:00").param("end", "10:00")).hasStatus(404);
        assertThat(jdbc.queryForObject("select count(*) from schedule_exception", Integer.class)).isZero();
    }

    // --- Details and drag and drop ---

    @Test
    void anAppointmentOpensItsDetailsAndCanBeDragged() {
        Appointment booked = data.appointment(doctor, data.patient(clinic, "Rana", "+96170123456", Language.AR),
                Instant.parse("2026-10-21T06:30:00Z"), Duration.ofMinutes(30));

        assertThat(get(staff, "/week?doctorId={id}&start=2026-10-21", doctor.getId())).bodyText()
                .contains("/appointments/" + booked.getId() + "?returnTo=week", "data-move-who=\"Rana\"",
                        "data-move-id=\"" + booked.getId() + "\"", "data-drop-time=\"09:00\"", "id=\"move-form\"");
        assertThat(get(staff, "/appointments/{id}?returnTo=week", booked.getId())).hasStatusOk().bodyText()
                .contains("Rana", "+96170123456", "Dr. Khoury", "Wednesday 21 October 2026", "9:30 AM – 10:00 AM",
                        "/appointments/" + booked.getId() + "/reschedule?returnTo=week", "Cancel appointment");
    }

    @Test
    void droppingOnAFreeTimeMovesTheAppointmentAndStaysOnWeek() {
        Appointment booked = data.appointment(doctor, data.patient(clinic, "Rana", "+96170123456", Language.AR),
                Instant.parse("2026-10-21T06:30:00Z"), Duration.ofMinutes(30));

        assertThat(post(staff, "/week/move").param("appointmentId", booked.getId().toString())
                .param("date", "2026-10-21").param("time", "09:00"))
                .hasRedirectedUrl("/week?doctorId=" + doctor.getId() + "&start=2026-10-19")
                .flash().containsEntry("notice", "notice.moved").containsEntry("focusDay", "2026-10-21");

        Appointment moved = appointments.findByIdAndClinicId(booked.getId(), clinic.getId()).orElseThrow();
        assertThat(moved.getStartsAt()).isEqualTo(Instant.parse("2026-10-21T06:00:00Z"));
        assertThat(moved.getEndsAt()).isEqualTo(Instant.parse("2026-10-21T06:30:00Z"));
    }

    @Test
    void aMoveOntoATakenTimeOrOfAFinishedAppointmentIsRefused() {
        Patient rana = data.patient(clinic, "Rana", "+96170123456", Language.AR);
        Appointment booked = data.appointment(doctor, rana, Instant.parse("2026-10-21T06:30:00Z"), Duration.ofMinutes(30));
        data.appointment(doctor, data.patient(clinic), Instant.parse("2026-10-21T06:00:00Z"), Duration.ofMinutes(30));
        Appointment past = data.appointment(doctor, rana, Instant.parse("2026-10-14T06:00:00Z"), Duration.ofMinutes(30));

        assertThat(post(staff, "/week/move").param("appointmentId", booked.getId().toString())
                .param("date", "2026-10-21").param("time", "09:00")).flash().containsEntry("error", "error.slot-taken");
        assertThat(post(staff, "/week/move").param("appointmentId", past.getId().toString())
                .param("date", "2026-10-21").param("time", "09:00")).flash().containsEntry("error", "error.not-allowed");
        assertThat(appointments.findByIdAndClinicId(booked.getId(), clinic.getId()).orElseThrow().getStartsAt())
                .isEqualTo(Instant.parse("2026-10-21T06:30:00Z"));
    }

    @Test
    void cancellingFromTheDetailsGoesBackToWeek() {
        Appointment booked = data.appointment(doctor, data.patient(clinic, "Rana", "+96170123456", Language.AR),
                Instant.parse("2026-10-21T06:30:00Z"), Duration.ofMinutes(30));

        assertThat(post(staff, "/appointments/{id}/cancel", booked.getId()).param("returnTo", "week"))
                .hasRedirectedUrl("/week?doctorId=" + doctor.getId() + "&start=2026-10-19")
                .flash().containsEntry("focusDay", "2026-10-21");
        assertThat(appointments.findByIdAndClinicId(booked.getId(), clinic.getId()).orElseThrow().getStatus())
                .isEqualTo(AppointmentStatus.CANCELLED);
    }

    @Test
    void anotherClinicsAppointmentCannotBeOpenedOrMoved() {
        Clinic other = data.clinic("B");
        Appointment theirs = data.appointment(data.doctor(other, 30), data.patient(other),
                Instant.parse("2026-10-21T06:30:00Z"), Duration.ofMinutes(30));

        assertThat(get(staff, "/appointments/{id}", theirs.getId())).hasStatus(404);
        assertThat(post(staff, "/week/move").param("appointmentId", theirs.getId().toString())
                .param("date", "2026-10-21").param("time", "09:00")).hasStatus(404);
    }
}
