package app.clinicreminders.clinic;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.appointment.AppointmentSource;
import app.clinicreminders.appointment.AppointmentService;
import app.clinicreminders.clinic.DemoClinic.Seeded;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.staff.StaffRole;
import app.clinicreminders.staff.StaffUser;
import app.clinicreminders.staff.StaffUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/** The "Dr. Demo" seed for sales demos (SPEC section 5.5). "Now" is Wednesday 14 October 2026. */
class DemoClinicIT extends IntegrationTest {

    @Autowired
    DemoClinic demo;

    @Autowired
    ClinicRepository clinics;

    @Autowired
    StaffUserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    AppointmentService appointmentService;

    @Test
    void seedsFourWeeksAroundTodayWithSomethingOnEveryPage() {
        Seeded seeded = demo.seed();

        assertThat(seeded.created()).isTrue();
        Clinic clinic = clinics.findByWaPhoneNumberId(DemoClinic.PHONE_NUMBER_ID).orElseThrow();
        assertThat(clinic.getId()).isEqualTo(seeded.clinicId());
        assertThat(clinic.getName()).isEqualTo("Dr. Demo");
        assertThat(clinic.getWaAccessTokenEncrypted()).as("nothing can reach a real number").isNull();
        assertThat(clinic.getAddress()).isNotBlank();
        assertThat(count("doctor", clinic)).isEqualTo(2);
        assertThat(count("patient", clinic)).isEqualTo(13);
        assertThat(count("appointment", clinic)).isEqualTo(seeded.appointments()).isGreaterThan(80);

        Instant now = clock.instant();
        assertThat(statuses(clinic, "starts_at < ?", now.minus(Duration.ofHours(1))))
                .containsOnly("ARRIVED", "NO_SHOW", "CANCELLED").contains("ARRIVED", "NO_SHOW", "CANCELLED");
        assertThat(statuses(clinic, "starts_at > ?", now)).containsOnly("BOOKED", "CONFIRMED", "CANCELLED")
                .contains("BOOKED", "CONFIRMED");
        assertThat(jdbc.queryForObject("""
                select count(*) from reminder r join appointment a on a.id = r.appointment_id
                where a.clinic_id = ? and r.status = 'PENDING' and a.starts_at < ?""", Integer.class,
                clinic.getId(), now.atOffset(ZoneOffset.UTC))).as("no reminders for the past").isZero();
        assertThat(jdbc.queryForObject("select count(*) from reminder where clinic_id = ? and status = 'PENDING'",
                Integer.class, clinic.getId())).isGreaterThan(20);
        assertThat(jdbc.queryForObject("select count(*) from conversation where clinic_id = ? and needs_human",
                Integer.class, clinic.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from message_log where clinic_id = ? and status = 'FAILED'",
                Integer.class, clinic.getId())).isEqualTo(1);
        assertThat(whatsApp.recent()).as("seeding sends nothing").isEmpty();

        StaffUser login = users.findByUsernameIgnoreCase(DemoClinic.LOGIN).orElseThrow();
        assertThat(login.getClinicId()).isEqualTo(clinic.getId());
        assertThat(login.getRole()).isEqualTo(StaffRole.OWNER);
        assertThat(passwordEncoder.matches(seeded.login().password(), login.getPasswordHash())).isTrue();
    }

    @Test
    void seedingAgainRefreshesOnlyTheDemoClinicAroundTheNewToday() {
        Clinic real = data.connectedClinic("Dr. Demo", "pn-real");
        Doctor doctor = data.doctor(real, 30);
        Patient patient = data.patient(real);
        appointmentService.book(real.getId(), doctor.getId(), patient.getId(), Instant.parse("2026-10-20T07:00:00Z"),
                AppointmentSource.MANUAL);
        Seeded first = demo.seed();

        clock.setInstant(clock.instant().plus(Duration.ofDays(30)));
        Seeded second = demo.seed();

        assertThat(second.created()).isFalse();
        assertThat(second.clinicId()).isEqualTo(first.clinicId());
        Clinic clinic = clinics.findById(second.clinicId()).orElseThrow();
        assertThat(count("doctor", clinic)).isEqualTo(2);
        assertThat(count("patient", clinic)).isEqualTo(13);
        assertThat(jdbc.queryForObject("select min(starts_at) >= ? from appointment where clinic_id = ?", Boolean.class,
                clock.instant().minus(Duration.ofDays(15)).atOffset(ZoneOffset.UTC), clinic.getId())).isTrue();
        assertThat(count("appointment", real)).as("a real clinic with the same name is untouched").isEqualTo(1);
        assertThat(count("patient", real)).isEqualTo(1);

        String hash = users.findByUsernameIgnoreCase(DemoClinic.LOGIN).orElseThrow().getPasswordHash();
        assertThat(passwordEncoder.matches(first.login().password(), hash)).isFalse();
        assertThat(passwordEncoder.matches(second.login().password(), hash)).isTrue();
    }

    private int count(String table, Clinic clinic) {
        return jdbc.queryForObject("select count(*) from " + table + " where clinic_id = ?", Integer.class, clinic.getId());
    }

    private List<String> statuses(Clinic clinic, String condition, Instant at) {
        return jdbc.queryForList("select distinct status from appointment where clinic_id = ? and " + condition,
                String.class, clinic.getId(), at.atOffset(ZoneOffset.UTC));
    }
}
