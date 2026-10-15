package app.clinicreminders.scheduling;

import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.clinic.Clinic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SchedulingRepositoriesIT extends IntegrationTest {

    @Autowired
    DoctorRepository doctors;

    @Autowired
    ScheduleRuleRepository rules;

    @Autowired
    ScheduleExceptionRepository exceptions;

    private Clinic clinicA;
    private Clinic clinicB;
    private Doctor doctorA;
    private Doctor doctorB;

    @BeforeEach
    void twoClinics() {
        clinicA = data.clinic("A");
        clinicB = data.clinic("B");
        doctorA = data.doctor(clinicA, 30);
        doctorB = data.doctor(clinicB, 30);
    }

    @Test
    void doctorIsFoundOnlyInItsOwnClinic() {
        assertThat(doctors.findByIdAndClinicId(doctorA.getId(), clinicA.getId())).isPresent();
        assertThat(doctors.findByIdAndClinicId(doctorA.getId(), clinicB.getId())).isEmpty();
    }

    @Test
    void rulesAreFoundOnlyForTheClinicAndDoctorAsked() {
        data.weekly(doctorA, MONDAY, "09:00", "12:00");
        data.weekly(doctorA, TUESDAY, "15:00", "18:00");
        data.weekly(doctorB, MONDAY, "08:00", "10:00");
        Doctor otherDoctorOfA = data.doctor(clinicA, 30);
        data.weekly(otherDoctorOfA, MONDAY, "10:00", "11:00");

        assertThat(rules.findByClinicIdAndDoctorId(clinicA.getId(), doctorA.getId()))
                .extracting(ScheduleRule::getDayOfWeek)
                .containsExactlyInAnyOrder(MONDAY, TUESDAY);
        assertThat(rules.findByClinicIdAndDoctorId(clinicA.getId(), doctorB.getId())).isEmpty();
    }

    @Test
    void exceptionsAreFoundOnlyForTheClinicAndInclusiveDateRange() {
        LocalDate first = LocalDate.of(2026, 11, 2);
        LocalDate last = LocalDate.of(2026, 11, 8);
        data.closedOn(doctorA, first.minusDays(1));
        data.closedOn(doctorA, first);
        data.hoursOn(doctorA, last, "10:00", "12:00");
        data.closedOn(doctorA, last.plusDays(1));
        data.closedOn(doctorB, first);

        assertThat(exceptions.findByClinicIdAndDoctorIdAndExceptionDateBetween(clinicA.getId(), doctorA.getId(), first, last))
                .extracting(ScheduleException::getExceptionDate)
                .containsExactlyInAnyOrder(first, last);
        assertThat(exceptions.findByClinicIdAndDoctorIdAndExceptionDateBetween(clinicA.getId(), doctorB.getId(), first, last))
                .isEmpty();
    }
}
