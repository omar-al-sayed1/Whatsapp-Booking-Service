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

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentEvents.CancelledBy;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentService;
import app.clinicreminders.appointment.AppointmentSource;
import app.clinicreminders.appointment.AppointmentStatus;
import app.clinicreminders.booking.BookingReplySender.BookingReply;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.Language;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.patient.PatientService;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.scheduling.Slot;
import app.clinicreminders.scheduling.SlotService;
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
    private final ApplicationEventPublisher events;
    private final Clock clock;

    BookingFlow(ConversationRepository conversations, ClinicRepository clinics, PatientRepository patients,
                PatientService patientService, DoctorRepository doctors, AppointmentRepository appointments,
                AppointmentService appointmentService, SlotService slots, BookingMessages messages,
                ApplicationEventPublisher events, Clock clock) {
        this.conversations = conversations;
        this.clinics = clinics;
        this.patients = patients;
        this.patientService = patientService;
        this.doctors = doctors;
        this.appointments = appointments;
        this.appointmentService = appointmentService;
        this.slots = slots;
        this.messages = messages;
        this.events = events;
        this.clock = clock;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void onInbound(InboundMessageReceived event) {
        InboundMessage message = event.message();
        if (message.content() instanceof QuickReply) {
            return; // Confirm or Cancel on a reminder template: ReminderReplies handles it.
        }
        long clinicId = event.clinicId();
        String phone = message.phoneE164();
        if (phone == null) {
            log.info("Clinic {}: message from a WhatsApp user whose number Meta didn't share, not answered", clinicId);
            return;
        }
        Instant now = clock.instant();
        // Created already expired, so a new number starts like an idle conversation: at the menu.
        conversations.insertIfAbsent(clinicId, phone, ConversationState.MENU.name(), now);
        Conversation conversation = conversations.findForUpdateByClinicIdAndPatientPhone(clinicId, phone).orElseThrow();
        Patient patient = event.patientId() == null ? null
                : patients.findByIdAndClinicId(event.patientId(), clinicId).orElse(null);
        if (patient != null && patient.isOptedOut()) {
            log.info("Clinic {}: opted-out patient {} wrote, flagged for the secretary", clinicId, patient.getId());
            conversation.flagForHuman();
            return;
        }
        Clinic clinic = clinics.findById(clinicId).orElseThrow(() -> new NotFoundException("Clinic", clinicId));
        BookingContext stored = BookingContext.fromJson(conversation.getContextJson());
        boolean fresh = conversation.isExpired(now) || stored.language() == null;
        BookingContext context = fresh ? BookingContext.start(languageOf(clinic, patient, message.content())) : stored;

        Step step = new Turn(clinic, phone, patient, message, context.language(), now)
                .answer(fresh ? null : conversation.getState(), context, message.content());

        if (step.needsHuman()) {
            conversation.flagForHuman();
        }
        conversation.moveTo(step.state(), step.context().toJson(), now.plus(SESSION));
        if (step.reply() != null) {
            events.publishEvent(new BookingReply(clinicId, step.reply(), step.appointmentId()));
        }
    }

    /** An existing patient keeps their language; a new number gets Arabic if they wrote Arabic, else the clinic's. */
    private static Language languageOf(Clinic clinic, @Nullable Patient patient, InboundContent content) {
        if (patient != null) {
            return patient.getLanguage();
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
        private final @Nullable Patient patient;
        private final InboundMessage message;
        private final Language language;
        private final Instant now;

        Turn(Clinic clinic, String phone, @Nullable Patient patient, InboundMessage message, Language language,
             Instant now) {
            this.clinic = clinic;
            this.clinicId = clinic.getId();
            this.zone = clinic.getTimezone();
            this.phone = phone;
            this.patient = patient;
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
                return unrecognised(state, context);
            }
            boolean booking = state == ConversationState.CHOOSE_DOCTOR || state == ConversationState.CHOOSE_DAY
                    || state == ConversationState.CHOOSE_SLOT || state == ConversationState.CONFIRM;
            return switch (tap.kind()) {
                case "doctor" -> booking ? chooseDoctor(context, tap.value()) : current(state, context);
                case "day" -> booking ? chooseDay(context, tap.value()) : current(state, context);
                case "slot" -> booking ? chooseTime(context, tap.value()) : current(state, context);
                case "slots" -> state == ConversationState.CHOOSE_SLOT
                        ? times(context.withDay(context.day(), context.page() + 1), false) : current(state, context);
                case "confirm" -> state == ConversationState.CONFIRM ? confirm(context, tap.value()) : current(state, context);
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
                case MY_APPOINTMENT -> myAppointment(context);
                case DONE -> new Step(state, context, null, null, false);
            };
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

        private Step confirmation(BookingContext context) {
            if (context.doctorId() == null || context.slot() == null) {
                return menu(context, false);
            }
            return new Step(ConversationState.CONFIRM, context,
                    messages.confirm(phone, language, doctorName(context.doctorId()), context.slot(), zone), null, false);
        }

        private Step confirm(BookingContext context, String answer) {
            if (context.doctorId() == null || context.day() == null || context.slot() == null) {
                return menu(context, false);
            }
            return switch (answer) {
                case "yes" -> bookTime(context, context.doctorId(), context.day(), context.slot());
                case "other" -> times(context.withDay(context.day(), context.page()), false);
                case "cancel" -> new Step(ConversationState.DONE, context.cleared(),
                        messages.notBooked(phone, language), null, false);
                default -> confirmation(context);
            };
        }

        private Step bookTime(BookingContext context, long doctorId, LocalDate day, Instant time) {
            BookingContext sameDay = context.withDay(day, context.page());
            if (!freeTimes(doctorId, day).contains(time)) {
                return times(sameDay, true);
            }
            Optional<Appointment> booked = appointmentService.tryBook(clinicId, doctorId, patientForBooking().getId(),
                    time, AppointmentSource.WHATSAPP);
            if (booked.isEmpty()) {
                log.info("Clinic {}: lost the race for doctor {} at {}, offering other times", clinicId, doctorId, time);
                return times(sameDay, true);
            }
            return new Step(ConversationState.DONE, context.cleared(),
                    messages.booked(phone, language, doctorName(doctorId), time, zone), booked.get().getId(), false);
        }

        /** The sender as a patient. A new number becomes one, named after their WhatsApp profile. */
        private Patient patientForBooking() {
            if (patient != null) {
                return patient;
            }
            String profileName = message.profileName();
            String name = profileName == null || profileName.isBlank() ? phone : profileName;
            Patient created = patientService.findOrCreate(clinicId, phone, name, language);
            // No patient of this clinic has the id, or the webhook handler would have matched them.
            if (message.userId() != null && created.getWaUserId() == null) {
                created.linkWhatsAppUser(message.userId());
            }
            return created;
        }

        private Step myAppointment(BookingContext context) {
            Optional<Appointment> next = patient == null ? Optional.empty()
                    : appointments.findNextOpen(clinicId, patient.getId(), now);
            if (next.isEmpty()) {
                return menu(context, true);
            }
            Appointment appointment = next.get();
            return new Step(ConversationState.MY_APPOINTMENT, context.cleared().withAppointment(appointment.getId()),
                    messages.myAppointment(phone, language, doctorName(appointment.getDoctorId()),
                            appointment.getStartsAt(), zone, appointment.getId()),
                    appointment.getId(), false);
        }

        /** Cancel it (the acknowledgment is M3's) or Keep it, for the appointment shown. */
        private Step mine(BookingContext context, String value) {
            if (value.equals("keep")) {
                return new Step(ConversationState.DONE, context.cleared(), null, null, false);
            }
            Long appointmentId = value.startsWith("cancel:") ? parseLong(value.substring("cancel:".length())) : null;
            if (appointmentId == null || !appointmentId.equals(context.appointmentId()) || patient == null) {
                return myAppointment(context);
            }
            boolean open = appointments.findByIdAndClinicId(appointmentId, clinicId)
                    .filter(a -> a.getPatientId().equals(patient.getId()))
                    .filter(a -> a.getStatus() == AppointmentStatus.BOOKED || a.getStatus() == AppointmentStatus.CONFIRMED)
                    .filter(a -> a.getStartsAt().isAfter(now))
                    .isPresent();
            if (!open) {
                return myAppointment(context);
            }
            appointmentService.cancel(clinicId, appointmentId, CancelledBy.PATIENT);
            return new Step(ConversationState.DONE, context.cleared(), null, null, false);
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
