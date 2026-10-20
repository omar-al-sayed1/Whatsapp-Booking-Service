package app.clinicreminders.booking;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentEvents.CancelledBy;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentService;
import app.clinicreminders.appointment.AppointmentSource;
import app.clinicreminders.appointment.AppointmentStatus;
import app.clinicreminders.booking.BookingMessages.Upcoming;
import app.clinicreminders.booking.BookingReplySender.BookingReply;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.Language;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.patient.PatientService;
import app.clinicreminders.reminder.ReminderMessages;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.scheduling.Slot;
import app.clinicreminders.scheduling.SlotService;
import app.clinicreminders.whatsapp.MessageKind;
import app.clinicreminders.whatsapp.MessageLog;
import app.clinicreminders.whatsapp.MessageLogRepository;
import app.clinicreminders.whatsapp.OutboundMessage;
import app.clinicreminders.whatsapp.webhook.InboundContent;
import app.clinicreminders.whatsapp.webhook.InboundContent.ButtonReply;
import app.clinicreminders.whatsapp.webhook.InboundContent.ListReply;
import app.clinicreminders.whatsapp.webhook.InboundContent.QuickReply;
import app.clinicreminders.whatsapp.webhook.InboundContent.Text;
import app.clinicreminders.whatsapp.webhook.InboundContent.Unsupported;
import app.clinicreminders.whatsapp.webhook.InboundMessage;
import app.clinicreminders.whatsapp.webhook.InboundMessageReceived;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booking over WhatsApp (SPEC section 5.2): MENU → CHOOSE_DOCTOR (skipped with one doctor) →
 * CHOOSE_DAY → CHOOSE_SLOT → CONFIRM → DONE, plus MY_APPOINTMENT and INFO.
 *
 * <p>A number may belong to a family (a parent books for their children), so CONFIRM asks who the
 * appointment is for: one of the number's patients, or "Someone else", whose name is typed
 * (ENTER_NAME). A new number can also book under its WhatsApp name. My appointment shows the
 * appointments of everyone on the number.
 *
 * <p>Book another time, on the message telling a patient the doctor is away, starts a booking with
 * that doctor. Other template buttons (Confirm and Cancel on reminders) are ReminderReplies'.
 *
 * <p>Runs in the webhook inbox's transaction with the conversation row locked, so two quick taps
 * from one patient are handled in order and a failure retries the delivery with nothing applied.
 * Each message leads to at most one reply, sent after commit by {@link BookingReplySender}.
 *
 * <ul>
 *   <li>A new number, or a conversation idle for 30 minutes, gets the menu.</li>
 *   <li>The menu's buttons work at any time. While booking, the booking's earlier lists still work
 *       (the patient scrolls up and picks another day). Any other old tap re-sends the current
 *       step; after DONE old taps (a second tap on Confirm) are ignored.</li>
 *   <li>Typed text, voice notes and photos get the menu once per session and flag the
 *       conversation for the secretary; after that they get no reply. After DONE (usually
 *       "thanks") they are ignored until the session ends.</li>
 *   <li>Opted-out patients get no reply; their conversation is flagged. Senders whose number Meta
 *       hides are only logged, since we message by number.</li>
 *   <li>The booking itself is {@code INSERT ... ON CONFLICT DO NOTHING}: a patient who loses the
 *       race for a time is offered the day's remaining times.</li>
 * </ul>
 */
@Component
class BookingFlow {

    private static final Logger log = LoggerFactory.getLogger(BookingFlow.class);

    /** An idle conversation starts again at the menu after this long. */
    static final Duration SESSION = Duration.ofMinutes(30);
    /** Days with free times offered, looked for this many days ahead. */
    static final int DAYS_SHOWN = 7;
    static final int DAYS_SEARCHED = 30;
    /** Meta's limit on list rows. A day with more free times shows them 9 at a time plus "Later times". */
    static final int MAX_ROWS = 10;

    /** What one message leads to. */
    private record Step(ConversationState state, BookingContext context, @Nullable OutboundMessage reply,
                        @Nullable Long appointmentId, boolean needsHuman) {
    }

    /** Book another time, tapped after the clinic cancelled: that appointment's doctor and patient. */
    private record Rebook(long doctorId, @Nullable Long patientId) {
    }

    /** A tap on one of our buttons or list rows: id {@code day:2026-11-03} is kind "day", value "2026-11-03". */
    private record Tap(String kind, String value) {

        static @Nullable Tap of(InboundContent content) {
            String id = switch (content) {
                case ButtonReply button -> button.id();
                case ListReply row -> row.id();
                case Text text -> null;
                case QuickReply reply -> null;
                case Unsupported other -> null;
            };
            if (id == null) {
                return null;
            }
            int colon = id.indexOf(':');
            return colon < 0 ? new Tap(id, "") : new Tap(id.substring(0, colon), id.substring(colon + 1));
        }

        boolean is(String expected) {
            return kind.equals(expected);
        }
    }

    private final ConversationRepository conversations;
    private final ClinicRepository clinics;
    private final PatientRepository patients;
    private final PatientService patientService;
    private final DoctorRepository doctors;
    private final AppointmentRepository appointments;
    private final AppointmentService appointmentService;
    private final SlotService slots;
    private final BookingMessages messages;
    private final MessageLogRepository logs;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    BookingFlow(ConversationRepository conversations, ClinicRepository clinics, PatientRepository patients,
                PatientService patientService, DoctorRepository doctors, AppointmentRepository appointments,
                AppointmentService appointmentService, SlotService slots, BookingMessages messages,
                MessageLogRepository logs, ApplicationEventPublisher events, Clock clock) {
        this.conversations = conversations;
        this.clinics = clinics;
        this.patients = patients;
        this.patientService = patientService;
        this.doctors = doctors;
        this.appointments = appointments;
        this.appointmentService = appointmentService;
        this.slots = slots;
        this.messages = messages;
        this.logs = logs;
        this.events = events;
        this.clock = clock;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void onInbound(InboundMessageReceived event) {
        InboundMessage message = event.message();
        long clinicId = event.clinicId();
        Rebook rebook = message.content() instanceof QuickReply ? rebookFrom(clinicId, message) : null;
        if (message.content() instanceof QuickReply && rebook == null) {
            return; // Confirm or Cancel on a reminder template: ReminderReplies handles it.
        }
        String phone = message.phoneE164();
        if (phone == null) {
            log.info("Clinic {}: message from a WhatsApp user whose number Meta didn't share, not answered", clinicId);
            return;
        }
        Instant now = clock.instant();
        // Created already expired, so a new number starts like an idle conversation: at the menu.
        conversations.insertIfAbsent(clinicId, phone, ConversationState.MENU.name(), now);
        Conversation conversation = conversations.findForUpdateByClinicIdAndPatientPhone(clinicId, phone).orElseThrow();
        List<Patient> household = patients.findByClinicIdAndPhoneE164OrderById(clinicId, phone);
        if (household.stream().anyMatch(Patient::isOptedOut)) {
            log.info("Clinic {}: opted-out patient {} wrote, flagged for the secretary", clinicId, household.getFirst().getId());
            conversation.flagForHuman();
            return;
        }
        Clinic clinic = clinics.findById(clinicId).orElseThrow(() -> new NotFoundException("Clinic", clinicId));
        BookingContext stored = BookingContext.fromJson(conversation.getContextJson());
        boolean fresh = conversation.isExpired(now) || stored.language() == null;
        BookingContext context = fresh ? BookingContext.start(languageOf(clinic, household, message.content())) : stored;
        if (rebook != null) {
            // A booking starts again, in the language the cancelled patient's message was in.
            context = BookingContext.start(household.stream()
                    .filter(patient -> patient.getId().equals(rebook.patientId()))
                    .findFirst()
                    .map(Patient::getLanguage)
                    .orElse(context.language()));
        }

        Turn turn = new Turn(clinic, phone, household, message, context.language(), now);
        Step step = rebook != null ? turn.rebook(context, rebook.doctorId())
                : turn.answer(fresh ? null : conversation.getState(), context, message.content());

        if (step.needsHuman()) {
            conversation.flagForHuman();
        }
        conversation.moveTo(step.state(), step.context().toJson(), now.plus(SESSION));
        if (step.reply() != null) {
            events.publishEvent(new BookingReply(clinicId, step.reply(), step.appointmentId()));
        }
    }

    /** For a tap on Book another time: the appointment the clinic cancelled; else null. */
    private @Nullable Rebook rebookFrom(long clinicId, InboundMessage message) {
        if (message.contextMessageId() == null) {
            return null;
        }
        return logs.findByClinicIdAndWaMessageId(clinicId, message.contextMessageId())
                .filter(sent -> sent.getKind() == MessageKind.TEMPLATE
                        && ReminderMessages.DOCTOR_AWAY_TEMPLATE.equals(sent.getTemplateName()))
                .map(MessageLog::getAppointmentId)
                .flatMap(appointmentId -> appointments.findByIdAndClinicId(appointmentId, clinicId))
                .map(appointment -> new Rebook(appointment.getDoctorId(), appointment.getPatientId()))
                .orElse(null);
    }

    /** A known number keeps its (first) patient's language; a new one gets Arabic if they wrote Arabic, else the clinic's. */
    private static Language languageOf(Clinic clinic, List<Patient> household, InboundContent content) {
        if (!household.isEmpty()) {
            return household.getFirst().getLanguage();
        }
        String written = switch (content) {
            case Text text -> text.body();
            case ButtonReply button -> button.title();
            case ListReply row -> row.title();
            case QuickReply reply -> reply.text();
            case Unsupported other -> "";
        };
        boolean arabic = written.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.ARABIC);
        return arabic ? Language.AR : clinic.getDefaultLanguage();
    }

    /** One message from one sender: the steps it can lead to. */
    private final class Turn {

        private final Clinic clinic;
        private final long clinicId;
        private final ZoneId zone;
        private final String phone;
        /** The patients on the sender's number: none for a new number, several for a family. */
        private final List<Patient> household;
        private final InboundMessage message;
        private final Language language;
        private final Instant now;

        Turn(Clinic clinic, String phone, List<Patient> household, InboundMessage message, Language language,
             Instant now) {
            this.clinic = clinic;
            this.clinicId = clinic.getId();
            this.zone = clinic.getTimezone();
            this.phone = phone;
            this.household = household;
            this.message = message;
            this.language = language;
            this.now = now;
        }

        /** @param state null for a new or expired conversation */
        Step answer(@Nullable ConversationState state, BookingContext context, InboundContent content) {
            Tap tap = Tap.of(content);
            if (tap != null && tap.is("menu")) {
                return menuChoice(context, tap.value());
            }
            if (state == null) {
                return menu(context, false);
            }
            if (tap == null) {
                return state == ConversationState.ENTER_NAME && content instanceof Text text
                        ? enterName(context, text.body()) : unrecognised(state, context);
            }
            boolean booking = state == ConversationState.CHOOSE_DOCTOR || state == ConversationState.CHOOSE_DAY
                    || state == ConversationState.CHOOSE_SLOT || state == ConversationState.CONFIRM
                    || state == ConversationState.ENTER_NAME;
            boolean confirming = state == ConversationState.CONFIRM || state == ConversationState.ENTER_NAME;
            return switch (tap.kind()) {
                case "doctor" -> booking ? chooseDoctor(context, tap.value()) : current(state, context);
                case "day" -> booking ? chooseDay(context, tap.value()) : current(state, context);
                case "slot" -> booking ? chooseTime(context, tap.value()) : current(state, context);
                case "slots" -> state == ConversationState.CHOOSE_SLOT
                        ? times(context.withDay(context.day(), context.page() + 1), false) : current(state, context);
                case "confirm" -> confirming ? confirm(context, tap.value()) : current(state, context);
                case "mine" -> state == ConversationState.MY_APPOINTMENT ? mine(context, tap.value()) : current(state, context);
                default -> current(state, context);
            };
        }

        /** The step the patient is on, again: for a tap that no longer fits. */
        private Step current(ConversationState state, BookingContext context) {
            return switch (state) {
                case MENU, INFO -> menu(context, false);
                case CHOOSE_DOCTOR -> book(context);
                case CHOOSE_DAY -> days(context, false);
                case CHOOSE_SLOT -> times(context, false);
                case CONFIRM -> confirmation(context);
                case ENTER_NAME -> nameQuestion(context);
                case MY_APPOINTMENT -> myAppointment(context);
                case DONE -> new Step(state, context, null, null, false);
            };
        }

        /** Book another time, after the clinic cancelled: that doctor's free days, or the doctors if they no longer take bookings. */
        Step rebook(BookingContext context, long doctorId) {
            boolean bookable = doctors.findByIdAndClinicId(doctorId, clinicId)
                    .filter(Doctor::isActive)
                    .filter(doctor -> !doctor.isDeleted())
                    .isPresent();
            return bookable ? days(context.withDoctor(doctorId), false) : book(context);
        }

        private Step menuChoice(BookingContext context, String choice) {
            return switch (choice) {
                case "book" -> book(context);
                case "mine" -> myAppointment(context);
                case "info" -> new Step(ConversationState.INFO, context.cleared(),
                        messages.info(phone, language, clinic.getAddress(), clinic.getHoursText()), null, false);
                default -> menu(context, false);
            };
        }

        private Step menu(BookingContext context, boolean noUpcoming) {
            return new Step(ConversationState.MENU, context.cleared(), messages.menu(phone, language, noUpcoming),
                    null, false);
        }

        /**
         * Typed text, a voice note, a photo: the menu once per session, and the secretary is told.
         * Right after a booking or cancellation it is nearly always "thanks", so it is ignored.
         */
        private Step unrecognised(ConversationState state, BookingContext context) {
            if (state == ConversationState.DONE) {
                return new Step(state, context, null, null, false);
            }
            if (context.menuResent()) {
                return new Step(state, context, null, null, true);
            }
            return new Step(ConversationState.MENU, context.menuResentOnce(), messages.menu(phone, language, false),
                    null, true);
        }

        /** Nothing to offer: tell the patient the clinic will call, and flag the conversation so it does. */
        private Step callBack(BookingContext context, OutboundMessage reply) {
            return new Step(ConversationState.DONE, context.cleared(), reply, null, true);
        }

        private Step book(BookingContext context) {
            List<Doctor> active = doctors.findByClinicIdOrderByDisplayName(clinicId).stream()
                    .filter(Doctor::isActive)
                    .toList();
            if (active.isEmpty()) {
                return callBack(context, messages.noDoctors(phone, language));
            }
            if (active.size() == 1) {
                return days(context.withDoctor(active.getFirst().getId()), false);
            }
            if (active.size() > MAX_ROWS) {
                log.warn("Clinic {} has {} active doctors; WhatsApp lists the first {}", clinicId, active.size(), MAX_ROWS);
            }
            return new Step(ConversationState.CHOOSE_DOCTOR, context.cleared(),
                    messages.doctors(phone, language, active.subList(0, Math.min(active.size(), MAX_ROWS))), null, false);
        }

        private Step chooseDoctor(BookingContext context, String value) {
            Long doctorId = parseLong(value);
            boolean bookable = doctorId != null
                    && doctors.findByIdAndClinicId(doctorId, clinicId).filter(Doctor::isActive).isPresent();
            return bookable ? days(context.withDoctor(doctorId), false) : book(context);
        }

        /** @param full the day the patient picked has no free times left */
        private Step days(BookingContext context, boolean full) {
            if (context.doctorId() == null) {
                return menu(context, false);
            }
            Map<LocalDate, Integer> freeDays = freeDays(context.doctorId());
            if (freeDays.isEmpty()) {
                return callBack(context, messages.noFreeDays(phone, language));
            }
            return new Step(ConversationState.CHOOSE_DAY, context.withDoctor(context.doctorId()),
                    messages.days(phone, language, freeDays, full), null, false);
        }

        private Step chooseDay(BookingContext context, String value) {
            LocalDate day = parseDate(value);
            if (day == null || context.doctorId() == null) {
                return days(context, false);
            }
            return times(context.withDay(day, 0), false);
        }

        /** @param taken the time the patient picked has just been taken */
        private Step times(BookingContext context, boolean taken) {
            if (context.doctorId() == null || context.day() == null) {
                return menu(context, false);
            }
            List<Instant> free = freeTimes(context.doctorId(), context.day());
            if (free.isEmpty()) {
                return days(context, true);
            }
            int perPage = MAX_ROWS - 1;
            int page = context.page() * perPage < free.size() ? context.page() : 0;
            List<Instant> rest = free.subList(page * perPage, free.size());
            boolean more = rest.size() > MAX_ROWS;
            List<Instant> shown = more ? rest.subList(0, perPage) : rest;
            return new Step(ConversationState.CHOOSE_SLOT, context.withDay(context.day(), page),
                    messages.times(phone, language, context.day(), shown, more, zone, taken), null, false);
        }

        /** A time from the current day's list, or from an earlier day's list the patient went back to. */
        private Step chooseTime(BookingContext context, String value) {
            Instant time = parseEpochSecond(value);
            if (time == null || context.doctorId() == null) {
                return days(context, false);
            }
            LocalDate day = LocalDate.ofInstant(time, zone);
            BookingContext onDay = day.equals(context.day()) ? context : context.withDay(day, 0);
            if (!freeTimes(context.doctorId(), day).contains(time)) {
                return times(onDay, true);
            }
            return confirmation(onDay.withSlot(time));
        }

        /** Who the appointment is for: the number's patients, Someone else, Another time or Don't book. */
        private Step confirmation(BookingContext context) {
            if (context.doctorId() == null || context.slot() == null) {
                return menu(context, false);
            }
            return new Step(ConversationState.CONFIRM, context,
                    messages.confirm(phone, language, doctorName(context.doctorId()), context.slot(), zone,
                            household.subList(0, Math.min(household.size(), BookingMessages.MAX_PATIENT_ROWS)),
                            message.profileName()),
                    null, false);
        }

        private Step confirm(BookingContext context, String answer) {
            if (context.doctorId() == null || context.day() == null || context.slot() == null) {
                return menu(context, false);
            }
            if (answer.startsWith("patient:")) {
                Long patientId = parseLong(answer.substring("patient:".length()));
                return household.stream()
                        .filter(patient -> patient.getId().equals(patientId))
                        .findFirst()
                        .map(patient -> bookTime(context, () -> patient))
                        .orElseGet(() -> confirmation(context));
            }
            return switch (answer) {
                // Offered only while the number has no patient: once it has, its patients are listed instead.
                case "me" -> household.isEmpty() ? bookTime(context, this::underProfileName) : confirmation(context);
                case "new" -> nameQuestion(context);
                case "other" -> times(context.withDay(context.day(), context.page()), false);
                case "cancel" -> new Step(ConversationState.DONE, context.cleared(),
                        messages.notBooked(phone, language), null, false);
                default -> confirmation(context);
            };
        }

        private Step nameQuestion(BookingContext context) {
            if (context.doctorId() == null || context.slot() == null) {
                return menu(context, false);
            }
            return new Step(ConversationState.ENTER_NAME, context, messages.askName(phone, language), null, false);
        }

        /**
         * The name typed after Someone else: books for the number's patient of that name, or adds
         * them. Anything that isn't a name is treated like other typed text.
         */
        private Step enterName(BookingContext context, String typed) {
            String name = Patient.normalizeName(typed);
            if (!isName(name)) {
                return unrecognised(ConversationState.ENTER_NAME, context);
            }
            if (context.doctorId() == null || context.day() == null || context.slot() == null) {
                return menu(context, false);
            }
            return bookTime(context, () -> named(name));
        }

        /** Books the confirmed time for the patient {@code patientFor} gives, asked only once the time is still free. */
        private Step bookTime(BookingContext context, Supplier<Patient> patientFor) {
            long doctorId = context.doctorId();
            Instant time = context.slot();
            BookingContext sameDay = context.withDay(context.day(), context.page());
            if (!freeTimes(doctorId, context.day()).contains(time)) {
                return times(sameDay, true);
            }
            Patient patient = patientFor.get();
            Optional<Appointment> booked = appointmentService.tryBook(clinicId, doctorId, patient.getId(), time,
                    AppointmentSource.WHATSAPP);
            if (booked.isEmpty()) {
                log.info("Clinic {}: lost the race for doctor {} at {}, offering other times", clinicId, doctorId, time);
                return times(sameDay, true);
            }
            return new Step(ConversationState.DONE, context.cleared(),
                    messages.booked(phone, language, patient.getName(), doctorName(doctorId), time, zone),
                    booked.get().getId(), false);
        }

        /** A new number's first patient, named after their WhatsApp profile (or their number, without one). */
        private Patient underProfileName() {
            String profileName = message.profileName();
            return named(profileName == null || profileName.isBlank() ? phone : profileName);
        }

        /** The patient of this name on the sender's number, or a new one. */
        private Patient named(String name) {
            Patient patient = patientService.findOrCreate(clinicId, phone, name, language);
            // A new patient: the number's others (if any) already got the id from the webhook handler.
            if (message.userId() != null && patient.getWaUserId() == null) {
                patient.linkWhatsAppUser(message.userId());
            }
            return patient;
        }

        /** Everyone on the number's appointments to come: one is shown with Cancel it / Keep it, several as a list. */
        private Step myAppointment(BookingContext context) {
            List<Upcoming> upcoming = upcoming();
            if (upcoming.isEmpty()) {
                return menu(context, true);
            }
            if (upcoming.size() == 1) {
                return showAppointment(context, upcoming.getFirst());
            }
            return new Step(ConversationState.MY_APPOINTMENT, context.cleared(),
                    messages.myAppointments(phone, language, upcoming, zone), null, false);
        }

        private Step showAppointment(BookingContext context, Upcoming appointment) {
            return new Step(ConversationState.MY_APPOINTMENT, context.cleared().withAppointment(appointment.appointmentId()),
                    messages.myAppointment(phone, language, appointment, zone), appointment.appointmentId(), false);
        }

        /** Show one from the list, or Cancel it (the acknowledgment is M3's) or Keep it, for the appointment shown. */
        private Step mine(BookingContext context, String value) {
            if (value.equals("keep")) {
                return new Step(ConversationState.DONE, context.cleared(), null, null, false);
            }
            if (value.startsWith("show:")) {
                Long shown = parseLong(value.substring("show:".length()));
                return upcoming().stream()
                        .filter(appointment -> shown != null && appointment.appointmentId() == shown)
                        .findFirst()
                        .map(appointment -> showAppointment(context, appointment))
                        .orElseGet(() -> myAppointment(context));
            }
            Long appointmentId = value.startsWith("cancel:") ? parseLong(value.substring("cancel:".length())) : null;
            if (appointmentId == null || !appointmentId.equals(context.appointmentId())) {
                return myAppointment(context);
            }
            List<Long> ids = household.stream().map(Patient::getId).toList();
            boolean open = appointments.findByIdAndClinicId(appointmentId, clinicId)
                    .filter(a -> a.getPatientId() != null && ids.contains(a.getPatientId()))
                    .filter(a -> a.getStatus() == AppointmentStatus.BOOKED || a.getStatus() == AppointmentStatus.CONFIRMED)
                    .filter(a -> a.getStartsAt().isAfter(now))
                    .isPresent();
            if (!open) {
                return myAppointment(context);
            }
            appointmentService.cancel(clinicId, appointmentId, CancelledBy.PATIENT);
            return new Step(ConversationState.DONE, context.cleared(), null, null, false);
        }

        /**
         * The open appointments to come of everyone on the number, earliest first; at most a list's
         * rows. Each is named for whom when a family shares the number.
         */
        private List<Upcoming> upcoming() {
            if (household.isEmpty()) {
                return List.of();
            }
            Map<Long, String> names = household.stream().collect(Collectors.toMap(Patient::getId, Patient::getName));
            boolean family = household.size() > 1;
            return appointments.findUpcomingOf(clinicId, names.keySet(), now).stream()
                    .limit(MAX_ROWS)
                    .map(a -> new Upcoming(a.getId(), family ? names.get(a.getPatientId()) : null,
                            doctorName(a.getDoctorId()), a.getStartsAt()))
                    .toList();
        }

        /** The first DAYS_SHOWN days with free times and how many each has, in date order. */
        private Map<LocalDate, Integer> freeDays(long doctorId) {
            LocalDate today = LocalDate.ofInstant(now, zone);
            Map<LocalDate, Integer> freeDays = new LinkedHashMap<>();
            for (Slot slot : slots.freeSlots(clinicId, doctorId, today, today.plusDays(DAYS_SEARCHED - 1))) {
                LocalDate day = LocalDate.ofInstant(slot.start(), zone);
                if (freeDays.size() == DAYS_SHOWN && !freeDays.containsKey(day)) {
                    break;
                }
                freeDays.merge(day, 1, Integer::sum);
            }
            return freeDays;
        }

        private List<Instant> freeTimes(long doctorId, LocalDate day) {
            return slots.freeSlots(clinicId, doctorId, day, day).stream().map(Slot::start).sorted().toList();
        }

        private String doctorName(long doctorId) {
            return doctors.findByIdAndClinicId(doctorId, clinicId).map(Doctor::getDisplayName)
                    .orElseThrow(() -> new NotFoundException("Doctor", doctorId));
        }
    }

    /** A typed name: 2 to 60 characters, with at least one letter. */
    static boolean isName(String name) {
        return name.length() >= 2 && name.length() <= 60 && name.codePoints().anyMatch(Character::isLetter);
    }

    private static @Nullable Long parseLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static @Nullable Instant parseEpochSecond(String value) {
        Long seconds = parseLong(value);
        try {
            return seconds == null ? null : Instant.ofEpochSecond(seconds);
        } catch (DateTimeException e) {
            return null;
        }
    }
}
