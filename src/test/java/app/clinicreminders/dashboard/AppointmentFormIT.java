package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentService;
import app.clinicreminders.appointment.AppointmentSource;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.reminder.ReminderRepository;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.staff.StaffPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Manual entry and moving. Wednesday 21 October 2026, Beirut is UTC+3: 09:00 local is 06:00Z. */
class AppointmentFormIT extends DashboardTest {

    private static final String DATE = "2026-10-21";
    private static final Instant NINE = Instant.parse("2026-10-21T06:00:00Z");

    @Autowired
    AppointmentService appointmentService;

    @Autowired
    AppointmentRepository appointments;

    @Autowired
    PatientRepository patients;

    @Autowired
    ReminderRepository reminders;

    private Clinic clinic;
    private Doctor doctor;
    private StaffPrincipal staff;

    @BeforeEach
    void doctorWorkingWednesdayMornings() {
        clinic = data.connectedClinic("A", "pn-a");
        doctor = data.doctor(clinic, "Dr. Khoury", 30);
        data.weekly(doctor, DayOfWeek.WEDNESDAY, "09:00", "11:00");
        staff = secretaryOf(clinic);
    }

    @Test
    void theFormOffersTheDoctorsFreeTimes() {
        existing(NINE.plus(Duration.ofMinutes(30)));

        assertThat(get(staff, "/appointments/new?doctorId={d}&date={date}", doctor.getId(), DATE))
                .hasStatusOk().bodyText()
                .contains("value=\"09:00\"", "9:00 AM", "value=\"10:30\"")
                .doesNotContain("value=\"09:30\"");
        assertThat(get(staff, "/appointments/slots?doctorId={d}&date={date}", doctor.getId(), DATE))
                .hasStatusOk().bodyText().contains("id=\"slots\"", "value=\"10:00\"").doesNotContain("<html");
    }

    @Test
    void booksANewPatientTypedAsALocalNumber() {
        assertThat(book("09:30", "", "70 123 456", "Karim", "en"))
                .hasStatus3xxRedirection().headers().hasValue("Location", "/?date=" + DATE);

        Patient karim = patients.findByClinicIdAndPhoneE164(clinic.getId(), "+96170123456").orElseThrow();
        assertThat(karim.getName()).isEqualTo("Karim");
        assertThat(karim.getLanguage()).isEqualTo(Language.EN);
        Appointment appointment = onlyAppointment();
        assertThat(appointment.getStartsAt()).isEqualTo(NINE.plus(Duration.ofMinutes(30)));
        assertThat(appointment.getSource()).isEqualTo(AppointmentSource.MANUAL);
        assertThat(reminders.findByClinicIdAndAppointmentIdOrderByDueAt(clinic.getId(), appointment.getId())).hasSize(2);
    }

    @Test
    void aKnownPatientNeedsOnlyTheirNumber() {
        data.patient(clinic, "Rana", "+96170123456", Language.AR);

        book("09:00", "", "+961 70 123 456", "", "en");

        assertThat(patients.findByClinicIdAndPhoneE164(clinic.getId(), "+96170123456").orElseThrow().getName())
                .isEqualTo("Rana");
        assertThat(onlyAppointment().getStartsAt()).isEqualTo(NINE);
    }

    @Test
    void someoneCanBeSqueezedInBetweenSlots() {
        book("09:00", "09:45", "03 123 456", "Nour", "ar");

        assertThat(onlyAppointment().getStartsAt()).isEqualTo(NINE.plus(Duration.ofMinutes(45)));
    }

    @Test
    void mistakesAreShownOnTheForm() {
        assertThat(book("09:00", "", "12", "Karim", "en")).hasStatusOk().bodyText()
                .contains("That phone number does not look right.");
        assertThat(book("09:00", "", "70123456", "", "en")).hasStatusOk().bodyText()
                .contains("Enter the patient&#39;s name.");
        assertThat(book("", "", "70123456", "Karim", "en")).hasStatusOk().bodyText()
                .contains("Pick a free time or enter another time.");
        assertThat(appointmentCount()).isZero();
    }

    @Test
    void aTakenTimeIsReportedInsteadOfDoubleBooked() {
        existing(NINE);

        assertThat(book("09:00", "", "70123456", "Karim", "en")).hasStatusOk().bodyText()
                .contains("That time was just taken. Pick another.");
        assertThat(appointmentCount()).isEqualTo(1);
    }

    @Test
    void movesAnAppointment() {
        Appointment appointment = existing(NINE);

        assertThat(get(staff, "/appointments/{id}/reschedule", appointment.getId())).hasStatusOk().bodyText()
                .contains("Move appointment", "Dr. Khoury");
        assertThat(post(staff, "/appointments/{id}/reschedule", appointment.getId())
                .param("date", DATE).param("time", "10:30").param("otherTime", ""))
                .hasStatus3xxRedirection();

        assertThat(onlyAppointment().getStartsAt()).isEqualTo(NINE.plus(Duration.ofMinutes(90)));
    }

    @Test
    void anotherClinicsDoctorsAndAppointmentsAreOffLimits() {
        Clinic other = data.clinic("B");
        Doctor theirDoctor = data.doctor(other, 30);
        Appointment theirs = data.appointment(theirDoctor, data.patient(other), NINE, Duration.ofMinutes(30));

        assertThat(post(staff, "/appointments").param("doctorId", theirDoctor.getId().toString()).param("date", DATE)
                .param("time", "09:00").param("phone", "70123456").param("name", "Karim").param("language", "en"))
                .hasStatus(404);
        assertThat(get(staff, "/appointments/{id}/reschedule", theirs.getId())).hasStatus(404);
        assertThat(post(staff, "/appointments/{id}/reschedule", theirs.getId()).param("date", DATE).param("time", "10:00"))
                .hasStatus(404);
        assertThat(get(staff, "/appointments/slots?appointmentId={id}&date={date}", theirs.getId(), DATE)).hasStatus(404);
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult book(String time, String otherTime, String phone,
                                                                         String name, String language) {
        return post(staff, "/appointments")
                .param("doctorId", doctor.getId().toString())
                .param("date", DATE)
                .param("time", time)
                .param("otherTime", otherTime)
                .param("phone", phone)
                .param("name", name)
                .param("language", language)
                .exchange();
    }

    private Appointment existing(Instant startsAt) {
        Patient someone = data.patient(clinic, "Someone", "+96171999999", Language.AR);
        return appointmentService.book(clinic.getId(), doctor.getId(), someone.getId(), startsAt, AppointmentSource.MANUAL);
    }

    private Appointment onlyAppointment() {
        Long id = jdbc.queryForObject("select id from appointment", Long.class);
        return appointments.findByIdAndClinicId(id, clinic.getId()).orElseThrow();
    }

    private int appointmentCount() {
        return jdbc.queryForObject("select count(*) from appointment", Integer.class);
    }
}
