package app.clinicreminders;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.function.Function;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentStatus;
import app.clinicreminders.booking.Conversation;
import app.clinicreminders.booking.ConversationState;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.reminder.Reminder;
import app.clinicreminders.reminder.ReminderKind;
import app.clinicreminders.reminder.ReminderStatus;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.ScheduleException;
import app.clinicreminders.scheduling.ScheduleRule;
import app.clinicreminders.scheduling.TimeWindow;
import app.clinicreminders.staff.StaffRole;
import app.clinicreminders.staff.StaffUser;
import app.clinicreminders.whatsapp.MessageDirection;
import app.clinicreminders.whatsapp.MessageKind;
import app.clinicreminders.whatsapp.MessageLog;
import app.clinicreminders.whatsapp.MessageRate;
import app.clinicreminders.whatsapp.MessageStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Saves each entity and reads it back in a fresh persistence context. Schema validation only
 * checks column types; this catches converters, defaults and generated columns.
 */
class EntityMappingIT extends IntegrationTest {

    private static final Instant START = Instant.parse("2026-11-02T07:00:00Z");

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transaction;

    @Test
    void clinicUsesDefaultsAndStoresTheZoneAndLanguage() {
        Clinic clinic = saveAndReload(new Clinic("Clinic", Clinic.DEFAULT_ZONE, Language.EN), Clinic::getId);

        assertThat(clinic.getTimezone()).isEqualTo(Clinic.DEFAULT_ZONE);
        assertThat(clinic.getDefaultLanguage()).isEqualTo(Language.EN);
        assertThat(clinic.getQuietHoursStart()).isEqualTo(LocalTime.of(21, 0));
        assertThat(clinic.getQuietHoursEnd()).isEqualTo(LocalTime.of(8, 0));
        assertThat(clinic.isReminderH24Enabled()).isTrue();
        assertThat(clinic.isReminderH2Enabled()).isTrue();
        assertThat(clinic.getCreatedAt()).isNotNull();
        assertThat(column("select timezone || '/' || default_language from clinic")).isEqualTo("Asia/Beirut/en");
    }

    @Test
    void staffUsers() {
        Clinic clinic = data.clinic("Clinic");

        StaffUser secretary = saveAndReload(
                StaffUser.clinicStaff(clinic.getId(), "rana", "hash", StaffRole.SECRETARY, Language.AR), StaffUser::getId);
        StaffUser operator = saveAndReload(StaffUser.operator("omar", "hash", Language.EN), StaffUser::getId);

        assertThat(secretary.getClinicId()).isEqualTo(clinic.getId());
        assertThat(secretary.getRole()).isEqualTo(StaffRole.SECRETARY);
        assertThat(secretary.getLanguage()).isEqualTo(Language.AR);
        assertThat(secretary.isEnabled()).isTrue();
        assertThat(operator.getClinicId()).isNull();
        assertThat(operator.getRole()).isEqualTo(StaffRole.OPERATOR);
    }

    @Test
    void doctorAndSchedule() {
        Clinic clinic = data.clinic("Clinic");
        Doctor doctor = saveAndReload(new Doctor(clinic.getId(), "Dr. Haddad", 20), Doctor::getId);
        var window = new TimeWindow(LocalTime.of(9, 0), LocalTime.of(13, 30));

        ScheduleRule rule = saveAndReload(new ScheduleRule(clinic.getId(), doctor.getId(), DayOfWeek.MONDAY, window),
                ScheduleRule::getId);
        ScheduleException closed = saveAndReload(
                ScheduleException.closed(clinic.getId(), doctor.getId(), LocalDate.of(2026, 12, 25)), ScheduleException::getId);
        ScheduleException shortDay = saveAndReload(ScheduleException.withHours(clinic.getId(), doctor.getId(),
                LocalDate.of(2026, 12, 24), window), ScheduleException::getId);

        assertThat(doctor.getSlotMinutes()).isEqualTo(20);
        assertThat(doctor.isActive()).isTrue();
        assertThat(rule.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(rule.window()).isEqualTo(window);
        assertThat(column("select day_of_week::text from schedule_rule")).isEqualTo("1");
        assertThat(closed.hours()).isEmpty();
        assertThat(closed.getExceptionDate()).isEqualTo(LocalDate.of(2026, 12, 25));
        assertThat(shortDay.hours()).contains(window);
    }

    @Test
    void appointmentKeepsItsInstantsExactly() {
        Clinic clinic = data.clinic("Clinic");
        Doctor doctor = data.doctor(clinic, 30);
        Appointment saved = data.cancelledAppointment(doctor, data.patient(clinic), START, Duration.ofMinutes(45));

        Appointment appointment = reload(Appointment.class, saved.getId());

        assertThat(appointment.getStartsAt()).isEqualTo(START);
        assertThat(appointment.getEndsAt()).isEqualTo(START.plus(Duration.ofMinutes(45)));
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(appointment.getCancelledAt()).isNotNull();
        assertThat(column("select to_char(starts_at at time zone 'UTC', 'YYYY-MM-DD HH24:MI') from appointment"))
                .isEqualTo("2026-11-02 07:00");
    }

    @Test
    void reminder() {
        Clinic clinic = data.clinic("Clinic");
        Appointment appointment = data.appointment(data.doctor(clinic, 30), data.patient(clinic), START, Duration.ofMinutes(30));

        Reminder reminder = saveAndReload(
                new Reminder(clinic.getId(), appointment.getId(), ReminderKind.H24, START.minus(Duration.ofDays(1))),
                Reminder::getId);

        assertThat(reminder.getKind()).isEqualTo(ReminderKind.H24);
        assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.PENDING);
        assertThat(reminder.getAttempts()).isZero();
        assertThat(reminder.getDueAt()).isEqualTo(Instant.parse("2026-11-01T07:00:00Z"));
    }

    @Test
    void messageLogAndRate() {
        Clinic clinic = data.clinic("Clinic");

        MessageLog sent = saveAndReload(MessageLog.sent(clinic.getId(), null, MessageKind.TEMPLATE,
                "appointment_reminder", "wamid.test-1", "utility", new BigDecimal("0.0110")), MessageLog::getId);
        MessageLog failed = saveAndReload(MessageLog.failed(clinic.getId(), null, MessageKind.TEXT, null,
                131026, "Message undeliverable"), MessageLog::getId);
        MessageRate rate = saveAndReload(
                new MessageRate("LB", "utility", new BigDecimal("0.012300"), LocalDate.of(2026, 10, 1)), MessageRate::getId);

        assertThat(sent.getDirection()).isEqualTo(MessageDirection.OUT);
        assertThat(sent.getKind()).isEqualTo(MessageKind.TEMPLATE);
        assertThat(sent.getTemplateName()).isEqualTo("appointment_reminder");
        assertThat(sent.getWaMessageId()).isEqualTo("wamid.test-1");
        assertThat(sent.getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(sent.getPricingCategory()).isEqualTo("utility");
        assertThat(sent.getEstCostUsd()).isEqualByComparingTo("0.0110");
        assertThat(sent.getCreatedAt()).isNotNull();
        assertThat(failed.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(failed.getWaMessageId()).isNull();
        assertThat(failed.getErrorCode()).isEqualTo(131026);
        assertThat(failed.getErrorTitle()).isEqualTo("Message undeliverable");
        assertThat(rate.getUsdPerMessage()).isEqualByComparingTo("0.0123");
    }

    @Test
    void patientWithWhatsAppUserIdAndOptOut() {
        Clinic clinic = data.clinic("Clinic");
        Patient patient = new Patient(clinic.getId(), "Rana", "+96170123456", Language.AR);
        patient.linkWhatsAppUser("LB.13491208655302741918");
        patient.optOut();

        Patient reloaded = saveAndReload(patient, Patient::getId);

        assertThat(reloaded.getWaUserId()).isEqualTo("LB.13491208655302741918");
        assertThat(reloaded.isOptedOut()).isTrue();
        assertThat(reloaded.getLanguage()).isEqualTo(Language.AR);
    }

    @Test
    void conversationStoresItsContextAsJsonb() {
        Clinic clinic = data.clinic("Clinic");

        Conversation conversation = saveAndReload(
                new Conversation(clinic.getId(), "+9613123456", ConversationState.MENU, START), Conversation::getId);

        assertThat(conversation.getState()).isEqualTo(ConversationState.MENU);
        assertThat(conversation.getContextJson()).isEqualTo("{}");
        assertThat(conversation.isNeedsHuman()).isFalse();
        assertThat(conversation.getUpdatedAt()).isNotNull();
        assertThat(column("select jsonb_typeof(context_json) from conversation")).isEqualTo("object");
    }

    private <T> T saveAndReload(T entity, Function<T, Long> id) {
        transaction.executeWithoutResult(status -> entityManager.persist(entity));
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) entity.getClass();
        return reload(type, id.apply(entity));
    }

    private <T> T reload(Class<T> type, Long id) {
        return transaction.execute(status -> entityManager.find(type, id));
    }

    private String column(String sql) {
        return jdbc.queryForObject(sql, String.class);
    }
}
