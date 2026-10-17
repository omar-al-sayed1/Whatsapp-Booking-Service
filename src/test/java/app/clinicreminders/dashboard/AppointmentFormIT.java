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
    void typingAPhoneSaysWhetherThePatientIsKnown() {
        data.patient(clinic, "Rana Haddad", "+96170123456", Language.AR);
        data.patient(data.clinic("B"), "Someone Else", "+96171999999", Language.EN);

        assertThat(get(staff, "/appointments/patient-hint?phone=70 123 456")).hasStatusOk().bodyText()
                .contains("id=\"patient-hint\"", "Known patient: Rana Haddad").doesNotContain("<html");
        assertThat(get(staff, "/appointments/patient-hint?phone=71 999 999")).bodyText()
                .as("another clinic's patient is a new patient here").contains("New patient");
        assertThat(get(staff, "/appointments/patient-hint?phone=12")).bodyText()
                .doesNotContain("Known patient", "New patient");
    }

    @Test
    void thePickerOffersPatientsBookedBeforeAndSaysWhenTheyAreBooked() {
        Patient rana = data.patient(clinic, "Rana Haddad", "+96170123456", Language.AR);
        data.appointment(doctor, rana, NINE, Duration.ofMinutes(30));
        data.patient(clinic, "Rania Never Booked", "+96171000002", Language.AR);

        assertThat(get(staff, "/appointments/patients")).hasStatusOk().bodyText()
                .contains("id=\"patient-results\"", "Recently booked", "Rana Haddad", "data-phone=\"+96170123456\"",
                        "Booked for Wed 21 Oct")
                .doesNotContain("Rania Never Booked", "<html");
        assertThat(get(staff, "/appointments/patients?q=haddad")).bodyText()
                .contains("Matching patients", "Rana Haddad");
        assertThat(get(staff, "/appointments/patients?q=zzz")).bodyText()
                .contains("No patient with an earlier appointment matches.");
    }

    @Test
    void theDropdownStartsEmptyAndSaysWhenNobodyWasBookedYet() {
        assertThat(get(staff, "/appointments/new?doctorId={d}&date={date}", doctor.getId(), DATE)).bodyText()
                .contains("data-combo", "Choose a patient booked before", "id=\"combo-panel\"");
        assertThat(get(staff, "/appointments/patients")).bodyText()
                .contains("No patient has been booked yet.", "Type the new patient&#39;s details below.");
    }

    @Test
    void bookingFromWeekGoesBackToWeek() {
        assertThat(get(staff, "/appointments/new?doctorId={d}&date={date}&time=09:00&returnTo=week", doctor.getId(), DATE))
                .bodyText().contains("name=\"returnTo\" value=\"week\"", "/week?doctorId=" + doctor.getId());

        assertThat(post(staff, "/appointments").param("doctorId", doctor.getId().toString()).param("date", DATE)
                .param("time", "09:00").param("phone", "70123456").param("name", "Karim").param("language", "en")
                .param("returnTo", "week"))
                .hasRedirectedUrl("/week?doctorId=" + doctor.getId() + "&start=2026-10-19")
                .flash().containsEntry("notice", "notice.booked");
    }

    @Test
    void bookingFromThePatientsPageStartsWithTheirDetails() {
        data.patient(clinic, "Rana Haddad", "+96170123456", Language.AR);

        assertThat(get(staff, "/appointments/new?phone={phone}&name={name}", "70123456", "Rana Haddad")).hasStatusOk()
                .bodyText().contains("value=\"70123456\"", "value=\"Rana Haddad\"", "Known patient: Rana Haddad")
                .as("the dropdown shows them").contains(">Rana Haddad</strong>", ">+96170123456</span>");
    }

    @Test
    void anErrorIsShownWithTheFormNotAsAToast() {
        assertThat(book("09:00", "", "12", "Karim", "en")).hasStatusOk().bodyText()
                .contains("class=\"alert\"").doesNotContain("class=\"toast error\"");
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
