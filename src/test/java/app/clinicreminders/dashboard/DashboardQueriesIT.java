package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentView;
import app.clinicreminders.booking.Conversation;
import app.clinicreminders.booking.ConversationRepository;
import app.clinicreminders.booking.ConversationState;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.scheduling.ScheduleExceptionRepository;
import app.clinicreminders.scheduling.ScheduleRule;
import app.clinicreminders.scheduling.ScheduleRuleRepository;
import app.clinicreminders.staff.StaffRole;
import app.clinicreminders.staff.StaffUser;
import app.clinicreminders.staff.StaffUserRepository;
import app.clinicreminders.whatsapp.FailedSend;
import app.clinicreminders.whatsapp.MessageKind;
import app.clinicreminders.whatsapp.MessageLog;
import app.clinicreminders.whatsapp.MessageLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Autowired;

/** Every query added for the dashboard, each with its clinic-isolation case. */
class DashboardQueriesIT extends IntegrationTest {

    private static final Pageable PAGE = PageRequest.of(0, 50);

    private static final Instant TUESDAY = Instant.parse("2026-11-03T08:30:00Z");

    @Autowired
    StaffUserRepository users;

    @Autowired
    AppointmentRepository appointments;

    @Autowired
    PatientRepository patients;

    @Autowired
    ConversationRepository conversations;

    @Autowired
    MessageLogRepository logs;

    @Autowired
    DoctorRepository doctors;

    @Autowired
    ScheduleRuleRepository rules;

    @Autowired
    ScheduleExceptionRepository exceptions;

    private Clinic clinicA;
    private Clinic clinicB;
    private Doctor doctorA;
    private Patient patientA;

    @BeforeEach
    void twoClinics() {
        clinicA = data.clinic("A");
        clinicB = data.clinic("B");
        doctorA = data.doctor(clinicA, "Dr. A", 30);
        patientA = data.patient(clinicA, "Rana", "+96170123456", Language.AR);
    }

    @Test
    void staffUsers() {
        StaffUser user = users.save(StaffUser.clinicStaff(clinicA.getId(), "Rana.Sec", "{noop}x", StaffRole.SECRETARY, Language.AR));

        assertThat(users.findByUsernameIgnoreCase("rana.sec")).isPresent();
        assertThat(users.findByIdAndClinicId(user.getId(), clinicA.getId())).isPresent();
        assertThat(users.findByIdAndClinicId(user.getId(), clinicB.getId())).isEmpty();
    }

    @Test
    void appointmentViewsCarryThePatientAndStayInTheirClinic() {
        Appointment booked = data.appointment(doctorA, patientA, TUESDAY, Duration.ofMinutes(30));
        data.cancelledAppointment(doctorA, patientA, TUESDAY.plusSeconds(3600), Duration.ofMinutes(30));

        List<AppointmentView> views = appointments.findViews(clinicA.getId(), TUESDAY.minusSeconds(60), TUESDAY.plusSeconds(86_400));

        assertThat(views).hasSize(2);
        assertThat(views.getFirst().id()).isEqualTo(booked.getId());
        assertThat(views.getFirst().patientName()).isEqualTo("Rana");
        assertThat(views.getFirst().patientPhone()).isEqualTo("+96170123456");
        assertThat(appointments.findViews(clinicB.getId(), TUESDAY.minusSeconds(60), TUESDAY.plusSeconds(86_400))).isEmpty();
    }

    @Test
    void appointmentViewsOfDeletedPatientsHaveNoName() {
        data.appointment(doctorA, patientA, TUESDAY, Duration.ofMinutes(30));
        jdbc.update("delete from patient");

        assertThat(appointments.findViews(clinicA.getId(), TUESDAY.minusSeconds(60), TUESDAY.plusSeconds(60)))
                .singleElement().satisfies(view -> assertThat(view.patientName()).isNull());
    }

    @Test
    void aPatientsFutureAppointmentsStayInTheirClinic() {
        data.appointment(doctorA, patientA, TUESDAY, Duration.ofMinutes(30));

        assertThat(appointments.findByClinicIdAndPatientIdAndStartsAtAfter(clinicA.getId(), patientA.getId(), TUESDAY.minusSeconds(1)))
                .hasSize(1);
        assertThat(appointments.findByClinicIdAndPatientIdAndStartsAtAfter(clinicA.getId(), patientA.getId(), TUESDAY)).isEmpty();
        assertThat(appointments.findByClinicIdAndPatientIdAndStartsAtAfter(clinicB.getId(), patientA.getId(), TUESDAY.minusSeconds(1)))
                .isEmpty();
    }

    @Test
    void patientSearchStaysInItsClinic() {
        data.patient(clinicB, "Rana Elsewhere", "+96170123499", Language.AR);

        assertThat(patients.search(clinicA.getId(), "rana", "", PAGE)).extracting(Patient::getName).containsExactly("Rana");
        assertThat(patients.search(clinicA.getId(), "", "70123", PAGE)).extracting(Patient::getName).containsExactly("Rana");
        assertThat(patients.search(clinicB.getId(), "rana", "", PAGE)).extracting(Patient::getName).containsExactly("Rana Elsewhere");
        assertThat(patients.countSearch(clinicA.getId(), "rana", "")).isEqualTo(1);
        assertThat(patients.countSearch(clinicB.getId(), "rana", "")).isEqualTo(1);
    }

    @Test
    void patientSearchComesAPageAtATime() {
        for (int i = 0; i < 5; i++) {
            data.patient(clinicA, "Zed " + i, "+9617088800" + i, Language.AR);
        }

        assertThat(patients.search(clinicA.getId(), "zed", "", PageRequest.of(0, 2))).extracting(Patient::getName)
                .containsExactly("Zed 0", "Zed 1");
        assertThat(patients.search(clinicA.getId(), "zed", "", PageRequest.of(2, 2))).extracting(Patient::getName)
                .containsExactly("Zed 4");
        assertThat(patients.countSearch(clinicA.getId(), "zed", "")).isEqualTo(5);
    }

    @Test
    void conversations() {
        Conversation waiting = new Conversation(clinicA.getId(), "+96170123456", ConversationState.MENU, TUESDAY);
        waiting.flagForHuman();
        waiting = conversations.save(waiting);
        conversations.save(new Conversation(clinicB.getId(), "+96170123456", ConversationState.MENU, TUESDAY));

        assertThat(conversations.findByClinicIdAndNeedsHumanTrueOrderByUpdatedAtDesc(clinicA.getId())).hasSize(1);
        assertThat(conversations.findByClinicIdAndNeedsHumanTrueOrderByUpdatedAtDesc(clinicB.getId())).isEmpty();
        assertThat(conversations.countByClinicIdAndNeedsHumanTrue(clinicA.getId())).isEqualTo(1);
        assertThat(conversations.countByClinicIdAndNeedsHumanTrue(clinicB.getId())).isZero();
        assertThat(conversations.findByIdAndClinicId(waiting.getId(), clinicB.getId())).isEmpty();
        assertThat(conversations.deleteByClinicIdAndPatientPhone(clinicA.getId(), "+96170123456")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from conversation", Integer.class)).isEqualTo(1);
    }

    @Test
    void failedSendsStayInTheirClinic() {
        Appointment appointment = data.appointment(doctorA, patientA, TUESDAY, Duration.ofMinutes(30));
        logs.save(MessageLog.failed(clinicA.getId(), appointment.getId(), MessageKind.TEMPLATE, "appointment_reminder",
                131026, "Message undeliverable"));
        logs.save(MessageLog.sent(clinicA.getId(), appointment.getId(), MessageKind.TEMPLATE, "appointment_reminder",
                "wamid.ok", "utility", null));

        List<FailedSend> failed = logs.findFailedSince(clinicA.getId(), Instant.parse("2026-01-01T00:00:00Z"), PAGE);

        assertThat(failed).singleElement().satisfies(f -> {
            assertThat(f.patientName()).isEqualTo("Rana");
            assertThat(f.appointmentStartsAt()).isEqualTo(TUESDAY);
            assertThat(f.errorCode()).isEqualTo(131026);
        });
        assertThat(logs.findFailedSince(clinicB.getId(), Instant.parse("2026-01-01T00:00:00Z"), PAGE)).isEmpty();
        assertThat(logs.countFailedSince(clinicA.getId(), Instant.parse("2026-01-01T00:00:00Z"))).isEqualTo(1);
        assertThat(logs.countFailedSince(clinicB.getId(), Instant.parse("2026-01-01T00:00:00Z"))).isZero();
    }

    @Test
    void doctorsRulesAndDaysOffStayInTheirClinic() {
        data.doctor(clinicB, "Dr. B", 30);
        ScheduleRule rule = data.weekly(doctorA, DayOfWeek.MONDAY, "09:00", "12:00");
        Long dayOff = data.closedOn(doctorA, LocalDate.of(2026, 12, 25)).getId();

        assertThat(doctors.findByClinicIdOrderByDisplayName(clinicA.getId())).extracting(Doctor::getDisplayName)
                .containsExactly("Dr. A");
        assertThat(rules.findByIdAndClinicId(rule.getId(), clinicA.getId())).isPresent();
        assertThat(rules.findByIdAndClinicId(rule.getId(), clinicB.getId())).isEmpty();
        assertThat(exceptions.findByIdAndClinicId(dayOff, clinicB.getId())).isEmpty();
        assertThat(exceptions.findByClinicIdAndDoctorIdAndExceptionDate(clinicA.getId(), doctorA.getId(), LocalDate.of(2026, 12, 25)))
                .isPresent();
        assertThat(exceptions.findByClinicIdAndDoctorIdAndExceptionDate(clinicB.getId(), doctorA.getId(), LocalDate.of(2026, 12, 25)))
                .isEmpty();
        assertThat(exceptions.findByClinicIdAndDoctorIdAndExceptionDateGreaterThanEqualOrderByExceptionDate(
                clinicA.getId(), doctorA.getId(), LocalDate.of(2026, 12, 26))).isEmpty();
        assertThat(exceptions.findByClinicIdAndDoctorIdAndExceptionDateGreaterThanEqualOrderByExceptionDate(
                clinicB.getId(), doctorA.getId(), LocalDate.of(2026, 1, 1))).isEmpty();
    }
}
