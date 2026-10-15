package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.staff.StaffPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WeekPageIT extends DashboardTest {

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
}
