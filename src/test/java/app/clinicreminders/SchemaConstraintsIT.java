package app.clinicreminders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/** Exercises V1's constraints with plain SQL, so they hold no matter what the Java code does. */
class SchemaConstraintsIT extends IntegrationTest {

    private static final Instant NINE_AM = Instant.parse("2026-11-02T07:00:00Z");

    @Test
    void twoActiveAppointmentsForTheSameDoctorAndStartAreRejected() {
        long clinic = clinic();
        long doctor = doctor(clinic);
        appointment(clinic, doctor, patient(clinic), NINE_AM, "BOOKED");

        assertThatThrownBy(() -> appointment(clinic, doctor, patient(clinic), NINE_AM, "CONFIRMED"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("appointment_doctor_start_active_uq");
    }

    @Test
    void aCancelledAppointmentFreesItsStartTime() {
        long clinic = clinic();
        long doctor = doctor(clinic);
        appointment(clinic, doctor, patient(clinic), NINE_AM, "CANCELLED");

        assertThatCode(() -> appointment(clinic, doctor, patient(clinic), NINE_AM, "BOOKED")).doesNotThrowAnyException();
    }

    @Test
    void differentDoctorsCanStartAtTheSameTime() {
        long clinic = clinic();
        appointment(clinic, doctor(clinic), patient(clinic), NINE_AM, "BOOKED");

        assertThatCode(() -> appointment(clinic, doctor(clinic), patient(clinic), NINE_AM, "BOOKED"))
                .doesNotThrowAnyException();
    }

    @Test
    void anAppointmentCannotUseAnotherClinicsDoctor() {
        long clinicA = clinic();
        long clinicB = clinic();
        long doctorOfB = doctor(clinicB);

        assertThatThrownBy(() -> appointment(clinicA, doctorOfB, patient(clinicA), NINE_AM, "BOOKED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anAppointmentCannotUseAnotherClinicsPatient() {
        long clinicA = clinic();
        long clinicB = clinic();

        assertThatThrownBy(() -> appointment(clinicA, doctor(clinicA), patient(clinicB), NINE_AM, "BOOKED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aScheduleRuleCannotUseAnotherClinicsDoctor() {
        long clinicA = clinic();
        long doctorOfB = doctor(clinic());

        assertThatThrownBy(() -> jdbc.update("""
                insert into schedule_rule (clinic_id, doctor_id, day_of_week, start_time, end_time)
                values (?, ?, 1, '09:00', '12:00')""", clinicA, doctorOfB))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAPatientKeepsTheirAppointmentsAnonymously() {
        long clinic = clinic();
        long patient = patient(clinic);
        long appointment = appointment(clinic, doctor(clinic), patient, NINE_AM, "BOOKED");

        jdbc.update("delete from patient where id = ?", patient);

        var row = jdbc.queryForMap("select clinic_id, patient_id from appointment where id = ?", appointment);
        assertThat(row.get("patient_id")).isNull();
        assertThat(row.get("clinic_id")).isEqualTo(clinic);
    }

    @Test
    void cancelledAtIsSetExactlyWhenTheAppointmentIsCancelled() {
        long clinic = clinic();
        long doctor = doctor(clinic);
        long patient = patient(clinic);

        assertThatThrownBy(() -> jdbc.update("""
                insert into appointment (clinic_id, doctor_id, patient_id, starts_at, ends_at, status, source)
                values (?, ?, ?, now(), now() + interval '30 minutes', 'CANCELLED', 'MANUAL')""",
                clinic, doctor, patient))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anAppointmentMustEndAfterItStarts() {
        long clinic = clinic();
        long doctor = doctor(clinic);
        long patient = patient(clinic);

        assertThatThrownBy(() -> jdbc.update("""
                insert into appointment (clinic_id, doctor_id, patient_id, starts_at, ends_at, status, source)
                values (?, ?, ?, now(), now(), 'BOOKED', 'MANUAL')""", clinic, doctor, patient))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void operatorsHaveNoClinicAndEveryoneElseHasOne() {
        long clinic = clinic();
        String insert = "insert into staff_user (clinic_id, username, password_hash, role, language) values (?, ?, 'x', ?, 'en')";

        assertThatThrownBy(() -> jdbc.update(insert, clinic, "op", "OPERATOR"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, null, "sec", "SECRETARY"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void usernamesAreUniqueIgnoringCase() {
        long clinic = clinic();
        String insert = "insert into staff_user (clinic_id, username, password_hash, role, language) values (?, ?, 'x', 'SECRETARY', 'ar')";
        jdbc.update(insert, clinic, "Rana");

        assertThatThrownBy(() -> jdbc.update(insert, clinic(), "rana")).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void aClosedExceptionHasNoHoursAndAnOpenOneNeedsBoth() {
        long clinic = clinic();
        long doctor = doctor(clinic);
        String insert = """
                insert into schedule_exception (clinic_id, doctor_id, exception_date, closed, start_time, end_time)
                values (?, ?, ?::date, ?, ?::time, ?::time)""";

        assertThatThrownBy(() -> jdbc.update(insert, clinic, doctor, "2026-11-02", true, "09:00", "12:00"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, clinic, doctor, "2026-11-03", false, "09:00", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> jdbc.update(insert, clinic, doctor, "2026-11-04", true, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void phoneNumbersMustBeE164() {
        long clinic = clinic();
        String insert = "insert into patient (clinic_id, name, phone_e164, language) values (?, 'P', ?, 'ar')";

        assertThatThrownBy(() -> jdbc.update(insert, clinic, "03 123 456")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, clinic, "9613123456")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> jdbc.update(insert, clinic, "+9613123456")).doesNotThrowAnyException();
    }

    @Test
    void thePhoneIsUniquePerClinicButNotAcrossClinics() {
        long clinicA = clinic();
        String insert = "insert into patient (clinic_id, name, phone_e164, language) values (?, 'P', '+96171123456', 'ar')";
        jdbc.update(insert, clinicA);

        assertThatThrownBy(() -> jdbc.update(insert, clinicA)).isInstanceOf(DuplicateKeyException.class);
        assertThatCode(() -> jdbc.update(insert, clinic())).doesNotThrowAnyException();
    }

    @Test
    void onlyOnePendingReminderOfEachKindPerAppointment() {
        long clinic = clinic();
        long appointment = appointment(clinic, doctor(clinic), patient(clinic), NINE_AM, "BOOKED");
        String insert = "insert into reminder (clinic_id, appointment_id, kind, due_at, status) values (?, ?, 'H24', now(), ?)";
        jdbc.update(insert, clinic, appointment, "CANCELLED");
        jdbc.update(insert, clinic, appointment, "PENDING");

        assertThatThrownBy(() -> jdbc.update(insert, clinic, appointment, "PENDING"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void inboundMessagesWeDontHandleAreLoggedAsOther() {
        long clinic = clinic();
        String insert = "insert into message_log (clinic_id, direction, wa_message_id, kind) values (?, 'IN', ?, ?)";

        assertThatCode(() -> jdbc.update(insert, clinic, "wamid.1", "OTHER")).doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbc.update(insert, clinic, "wamid.2", "VIDEO"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aWhatsAppUserIdBelongsToOnePatientPerClinic() {
        long clinicA = clinic();
        long patient = patient(clinicA);
        long other = patient(clinicA);
        jdbc.update("update patient set wa_user_id = 'LB.1' where id = ?", patient);

        assertThatThrownBy(() -> jdbc.update("update patient set wa_user_id = 'LB.1' where id = ?", other))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatCode(() -> jdbc.update("update patient set wa_user_id = 'LB.1' where id = ?", patient(clinic())))
                .doesNotThrowAnyException();
    }

    // --- plain SQL fixtures ---

    private long clinic() {
        return jdbc.queryForObject("insert into clinic (name, default_language) values ('C', 'ar') returning id", Long.class);
    }

    private long doctor(long clinic) {
        return jdbc.queryForObject(
                "insert into doctor (clinic_id, display_name, slot_minutes) values (?, 'D', 30) returning id",
                Long.class, clinic);
    }

    private int phones = 0;

    private long patient(long clinic) {
        return jdbc.queryForObject(
                "insert into patient (clinic_id, name, phone_e164, language) values (?, 'P', ?, 'ar') returning id",
                Long.class, clinic, "+96103%06d".formatted(++phones));
    }

    private long appointment(long clinic, long doctor, long patient, Instant start, String status) {
        return jdbc.queryForObject("""
                insert into appointment (clinic_id, doctor_id, patient_id, starts_at, ends_at, status, source, cancelled_at)
                values (?, ?, ?, ?, ?, ?, 'MANUAL', ?) returning id""", Long.class,
                clinic, doctor, patient, utc(start), utc(start.plusSeconds(1800)), status,
                status.equals("CANCELLED") ? utc(start.minusSeconds(86_400)) : null);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
