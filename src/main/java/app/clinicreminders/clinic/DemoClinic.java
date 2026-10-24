package app.clinicreminders.clinic;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentEvents.CancelledBy;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentService;
import app.clinicreminders.appointment.AppointmentSource;
import app.clinicreminders.booking.Conversation;
import app.clinicreminders.booking.ConversationRepository;
import app.clinicreminders.booking.ConversationState;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.reminder.ReminderKind;
import app.clinicreminders.reminder.ReminderMessages;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.scheduling.ScheduleRule;
import app.clinicreminders.scheduling.ScheduleRuleRepository;
import app.clinicreminders.scheduling.Slot;
import app.clinicreminders.scheduling.SlotService;
import app.clinicreminders.scheduling.TimeWindow;
import app.clinicreminders.staff.StaffAccounts;
import app.clinicreminders.staff.StaffAccounts.NewPassword;
import app.clinicreminders.staff.StaffRole;
import app.clinicreminders.staff.StaffUser;
import app.clinicreminders.whatsapp.MessageCostEstimator;
import app.clinicreminders.whatsapp.MessageKind;
import app.clinicreminders.whatsapp.MessageLog;
import app.clinicreminders.whatsapp.MessageLogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The "Dr. Demo" clinic for sales demos (SPEC section 5.5): two doctors, a dozen patients and four
 * weeks of appointments around today, with planned reminders, a conversation waiting for the
 * secretary and a failed send, so every dashboard page has something to show. Past appointments
 * come with the reminders sent for them and most patients' confirmations, for the monthly report.
 *
 * <p>The clinic is found by its fake WhatsApp phone number id, never by name, so a real clinic is
 * never touched. Running it again replaces only the demo clinic's doctors, patients, appointments,
 * reminders, conversations and message log around the new "today", and gives its login a new
 * password. The clinic has no access token, so even with the real WhatsApp client nothing it
 * plans can reach the made-up patient numbers.
 */
@Service
public class DemoClinic {

    public static final String PHONE_NUMBER_ID = "demo-phone-number-id";
    public static final String LOGIN = "drdemo";

    /** Tables holding the demo clinic's data, children first. */
    private static final List<String> TENANT_TABLES = List.of("reminder", "message_log", "appointment", "conversation",
            "patient", "schedule_exception", "schedule_rule", "doctor");
    private static final int DAYS_BACK = 14;
    private static final int DAYS_AHEAD = 14;

    private record DemoPatient(String name, String phone, Language language) {
    }

    private static final List<DemoPatient> PATIENTS = List.of(
            new DemoPatient("رنا حداد", "+96170990001", Language.AR),
            // Her son, on her phone: a family shares a number.
            new DemoPatient("يوسف حداد", "+96170990001", Language.AR),
            new DemoPatient("جورج معلوف", "+96170990002", Language.AR),
            new DemoPatient("ليلى سكاف", "+96170990003", Language.AR),
            new DemoPatient("مارون عازار", "+96170990004", Language.AR),
            new DemoPatient("نادين أبو جودة", "+96170990005", Language.AR),
            new DemoPatient("خليل شديد", "+96170990006", Language.AR),
            new DemoPatient("ريتا الحاج", "+96170990007", Language.AR),
            new DemoPatient("طوني فرح", "+96170990008", Language.AR),
            new DemoPatient("سلمى نصار", "+96170990009", Language.AR),
            new DemoPatient("إيلي جبور", "+96170990010", Language.AR),
            new DemoPatient("Maya Khoury", "+96170990011", Language.EN),
            new DemoPatient("Karim Saab", "+96170990012", Language.EN));

    /** @param created false when an existing demo clinic was refreshed */
    public record Seeded(long clinicId, boolean created, NewPassword login, int appointments) {
    }

    private final ClinicRepository clinics;
    private final DoctorRepository doctors;
    private final ScheduleRuleRepository rules;
    private final PatientRepository patients;
    private final AppointmentRepository appointments;
    private final AppointmentService appointmentService;
    private final SlotService slots;
    private final ConversationRepository conversations;
    private final MessageLogRepository logs;
    private final MessageCostEstimator costs;
    private final StaffAccounts accounts;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    DemoClinic(ClinicRepository clinics, DoctorRepository doctors, ScheduleRuleRepository rules,
               PatientRepository patients, AppointmentRepository appointments, AppointmentService appointmentService,
               SlotService slots, ConversationRepository conversations, MessageLogRepository logs,
               MessageCostEstimator costs, StaffAccounts accounts, JdbcTemplate jdbc, Clock clock) {
        this.clinics = clinics;
        this.doctors = doctors;
        this.rules = rules;
        this.patients = patients;
        this.appointments = appointments;
        this.appointmentService = appointmentService;
        this.slots = slots;
        this.conversations = conversations;
        this.logs = logs;
        this.costs = costs;
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public Seeded seed() {
        Clinic existing = clinics.findByWaPhoneNumberId(PHONE_NUMBER_ID).orElse(null);
        Clinic clinic = existing == null ? createClinic() : existing;
        if (existing != null) {
            for (String table : TENANT_TABLES) {
                jdbc.update("delete from " + table + " where clinic_id = ?", clinic.getId());
            }
        }
        long clinicId = clinic.getId();

        Doctor demo = doctor(clinic, "د. ديمو", EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.SATURDAY), "09:00", "13:00");
        Doctor sara = doctor(clinic, "د. سارة نصّار",
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), "16:00", "19:00");
        List<Patient> people = PATIENTS.stream()
                .map(p -> patients.save(new Patient(clinicId, p.name(), p.phone(), p.language())))
                .toList();

        // A fixed seed: every run tells the same story around its own "today".
        Random random = new Random(8);
        List<Appointment> booked = new ArrayList<>();
        for (Doctor doctor : List.of(demo, sara)) {
            booked.addAll(appointments(clinic, doctor, people, random));
        }

        Conversation waiting = new Conversation(clinicId, "+96171990099", ConversationState.MENU, clock.instant());
        waiting.flagForHuman();
        conversations.save(waiting);
        booked.stream().filter(a -> a.getStartsAt().isAfter(clock.instant().plus(Duration.ofDays(1)))).findFirst()
                .ifPresent(a -> logs.save(MessageLog.failed(clinicId, a.getId(), MessageKind.TEMPLATE,
                        "appointment_reminder", 131026, "Message undeliverable")));

        return new Seeded(clinicId, existing == null, login(clinicId), booked.size());
    }

    private Clinic createClinic() {
        Clinic clinic = new Clinic("Dr. Demo", Clinic.DEFAULT_ZONE, Language.AR);
        clinic.connectWhatsApp(PHONE_NUMBER_ID, "demo-waba", null);
        clinic.changeContactDetails("شارع البرازيل، زحلة، البقاع",
                "الاثنين إلى السبت 9:00 - 13:00، وطب الأطفال الاثنين والأربعاء والجمعة 16:00 - 19:00");
        return clinics.save(clinic);
    }

    /** Every doctor follows the clinic's appointment length. */
    private Doctor doctor(Clinic clinic, String name, Set<DayOfWeek> days, String start, String end) {
        long clinicId = clinic.getId();
        Doctor doctor = doctors.save(new Doctor(clinicId, name, clinic.getSlotMinutes()));
        for (DayOfWeek day : days) {
            rules.save(new ScheduleRule(clinicId, doctor.getId(), day,
                    new TimeWindow(LocalTime.parse(start), LocalTime.parse(end))));
        }
        return doctor;
    }

    /**
     * Past days: mostly arrived, some no-shows and cancellations. Today and ahead: booked, or
     * confirmed close to the date, fewer the further out, with reminders planned as for any booking.
     */
    private List<Appointment> appointments(Clinic clinic, Doctor doctor, List<Patient> people, Random random) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, clinic.getTimezone());
        List<Appointment> made = new ArrayList<>();
        for (Slot slot : slots.allSlots(clinic.getId(), doctor.getId(), today.minusDays(DAYS_BACK), today.plusDays(DAYS_AHEAD))) {
            long daysAhead = Duration.between(now, slot.start()).toDays();
            double fill = slot.start().isBefore(now) ? 0.65 : Math.max(0.25, 0.7 - 0.03 * daysAhead);
            if (random.nextDouble() >= fill) {
                continue;
            }
            Patient patient = people.get(random.nextInt(people.size()));
            AppointmentSource source = random.nextDouble() < 0.35 ? AppointmentSource.WHATSAPP : AppointmentSource.MANUAL;
            double roll = random.nextDouble();
            if (slot.start().isAfter(now) && roll >= 0.05) {
                Appointment appointment = appointmentService.book(clinic.getId(), doctor.getId(), patient.getId(),
                        slot.start(), source);
                boolean soon = slot.start().isBefore(now.plus(Duration.ofDays(1)));
                if (random.nextDouble() < (soon ? 0.7 : 0.2)) {
                    appointment.confirm(now.minus(Duration.ofHours(1 + random.nextInt(20))));
                }
                made.add(appointment);
                continue;
            }
            // Statuses set directly: no reminders, and no cancellation messages to made-up numbers.
            Appointment appointment = new Appointment(clinic.getId(), doctor.getId(), patient.getId(), slot.start(),
                    slot.end(), source);
            boolean confirmed = random.nextDouble() < (roll < 0.22 ? 0.3 : 0.75);
            if (roll < 0.1) {
                Instant cancelledAt = slot.start().minus(Duration.ofHours(3 + random.nextInt(40)));
                appointment.cancel(cancelledAt.isBefore(now) ? cancelledAt : now, CancelledBy.PATIENT);
            } else {
                if (confirmed) {
                    appointment.confirm(slot.start().minus(Duration.ofHours(20)));
                }
                if (roll < 0.22) {
                    appointment.markNoShow(slot.start());
                } else {
                    appointment.markArrived();
                }
            }
            Appointment saved = appointments.save(appointment);
            remindersSent(clinic.getId(), saved, patient);
            made.add(saved);
        }
        return made;
    }

    /**
     * The reminders a past appointment had: the day before and 2 hours before, unless the patient had
     * cancelled by then; each with its message, priced if the operator has set Meta's rates.
     */
    private void remindersSent(long clinicId, Appointment appointment, Patient patient) {
        Instant start = appointment.getStartsAt();
        for (var reminder : List.of(
                Map.entry(ReminderKind.H24, Map.entry(ReminderMessages.H24_TEMPLATE, start.minus(Duration.ofHours(24)))),
                Map.entry(ReminderKind.H2, Map.entry(ReminderMessages.H2_TEMPLATE, start.minus(Duration.ofHours(2)))))) {
            Instant sentAt = reminder.getValue().getValue();
            if (appointment.getCancelledAt() != null && !appointment.getCancelledAt().isAfter(sentAt)) {
                continue;
            }
            String messageId = "wamid.DEMO-" + UUID.randomUUID();
            jdbc.update("""
                    insert into reminder (clinic_id, appointment_id, kind, due_at, status, attempts, sent_message_id, sent_at)
                    values (?, ?, ?, ?, 'SENT', 1, ?, ?)""", clinicId, appointment.getId(), reminder.getKey().name(),
                    utc(sentAt), messageId, utc(sentAt));
            jdbc.update("""
                    insert into message_log (clinic_id, appointment_id, direction, wa_message_id, kind, template_name,
                                             pricing_category, status, est_cost_usd, created_at)
                    values (?, ?, 'OUT', ?, 'TEMPLATE', ?, 'utility', 'READ', ?, ?)""", clinicId, appointment.getId(),
                    messageId, reminder.getValue().getKey(),
                    costs.estimate(patient.getPhoneE164(), "utility", LocalDate.ofInstant(sentAt, Clinic.DEFAULT_ZONE)),
                    utc(sentAt));
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private NewPassword login(long clinicId) {
        return accounts.logins(clinicId).stream()
                .filter(user -> user.getUsername().equalsIgnoreCase(LOGIN))
                .map(StaffUser::getId)
                .findFirst()
                .map(id -> accounts.resetPassword(clinicId, id))
                .orElseGet(() -> accounts.createClinicLogin(clinicId, LOGIN, StaffRole.OWNER, Language.AR));
    }
}
