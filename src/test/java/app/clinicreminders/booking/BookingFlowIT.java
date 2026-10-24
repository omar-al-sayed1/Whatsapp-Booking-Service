package app.clinicreminders.booking;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentEvents.CancelledBy;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentService;
import app.clinicreminders.appointment.AppointmentSource;
import app.clinicreminders.appointment.AppointmentStatus;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.Language;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.reminder.ReminderRepository;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.whatsapp.FakeWhatsAppClient.SentMessage;
import app.clinicreminders.whatsapp.MessageLogRepository;
import app.clinicreminders.whatsapp.OutboundMessage;
import app.clinicreminders.whatsapp.OutboundMessage.ListMessage;
import app.clinicreminders.whatsapp.OutboundMessage.ListRow;
import app.clinicreminders.whatsapp.OutboundMessage.ReplyButton;
import app.clinicreminders.whatsapp.OutboundMessage.ReplyButtonsMessage;
import app.clinicreminders.whatsapp.OutboundMessage.TextMessage;
import app.clinicreminders.whatsapp.dev.SimulatedWebhooks;
import app.clinicreminders.whatsapp.webhook.WebhookInbox;
import app.clinicreminders.whatsapp.webhook.WebhookInboxProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The booking conversation end to end (SPEC sections 5.2 and 8): real webhook inbox, real database,
 * fake WhatsApp. "Now" is Wednesday 14 October 2026, 12:00 in Beirut; the doctor works Monday to
 * Saturday 09:00-12:00 in 30-minute slots, so today has no free time left.
 */
class BookingFlowIT extends IntegrationTest {

    private static final String PHONE = "+96170123456";
    private static final String OTHER_PHONE = "+96171222333";
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 15);
    private static final Instant THU_0900 = Instant.parse("2026-10-15T06:00:00Z");
    private static final Instant THU_1030 = Instant.parse("2026-10-15T07:30:00Z");
    private static final String MENU_AR = "مرحبًا! كيف يمكننا مساعدتك؟";
    private static final String TAKEN_AR = "عذرًا، حُجز هذا الوقت للتو. اختر وقتًا آخر يوم الخميس 15 تشرين الأول.";

    @Autowired
    WebhookInbox inbox;

    @Autowired
    WebhookInboxProcessor processor;

    @Autowired
    ClinicRepository clinics;

    @Autowired
    DoctorRepository doctors;

    @Autowired
    PatientRepository patients;

    @Autowired
    AppointmentRepository appointments;

    @Autowired
    AppointmentService appointmentService;

    @Autowired
    ReminderRepository reminders;

    @Autowired
    MessageLogRepository logs;

    @Autowired
    TransactionTemplate transactions;

    private Clinic clinic;
    private Doctor khoury;

    @BeforeEach
    void clinicWithOneDoctor() {
        clinic = data.connectedClinic("A", "pn-a");
        khoury = data.doctor(clinic, "د. خوري", 30);
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day != DayOfWeek.SUNDAY) {
                data.weekly(khoury, day, "09:00", "12:00");
            }
        }
    }

    // --- Starting ---

    @Test
    void aNewNumberGetsTheMenuInArabicIfTheyWroteArabicElseInTheClinicsLanguage() {
        clinic.changeDefaultLanguage(Language.EN);
        clinics.save(clinic);

        say(PHONE, "مرحبا");
        say(OTHER_PHONE, "Hello");

        assertThat(lastTo(PHONE)).isInstanceOfSatisfying(ReplyButtonsMessage.class, menu -> {
            assertThat(menu.body()).isEqualTo(MENU_AR);
            assertThat(menu.buttons()).extracting(ReplyButton::id).containsExactly("menu:book", "menu:mine", "menu:info");
        });
        assertThat(lastTo(OTHER_PHONE)).isInstanceOfSatisfying(ReplyButtonsMessage.class,
                menu -> assertThat(menu.body()).isEqualTo("Hello! How can we help?"));
        assertThat(state()).isEqualTo(ConversationState.MENU);
        assertThat(flagged()).isFalse();
        assertThat(patients.findByClinicIdAndPhoneE164OrderById(clinic.getId(), PHONE)).as("no patient until they book").isEmpty();
    }

    @Test
    void aKnownPatientKeepsTheirLanguage() {
        data.patient(clinic, "John", PHONE, Language.EN);

        say("مرحبا");

        assertThat(lastTo(PHONE)).isInstanceOfSatisfying(ReplyButtonsMessage.class,
                menu -> assertThat(menu.body()).isEqualTo("Hello! How can we help?"));
    }

    // --- Booking ---

    @Test
    void aBookingTakesFiveMessagesAndCreatesThePatient() {
        say("مرحبا");

        tap("menu:book");
        assertThat(state()).isEqualTo(ConversationState.CHOOSE_DAY);
        assertThat(lastTo(PHONE)).isInstanceOfSatisfying(ListMessage.class, days -> {
            assertThat(days.body()).isEqualTo("اختر يومًا.");
            assertThat(days.rows()).extracting(ListRow::id).containsExactly("day:2026-10-15", "day:2026-10-16",
                    "day:2026-10-17", "day:2026-10-19", "day:2026-10-20", "day:2026-10-21", "day:2026-10-22");
            assertThat(days.rows().getFirst()).isEqualTo(new ListRow("day:2026-10-15", "الخميس 15 تشرين الأول", "أوقات متاحة: 6"));
        });

        tap("day:2026-10-15");
        assertThat(state()).isEqualTo(ConversationState.CHOOSE_SLOT);
        assertThat(rowIds(lastTo(PHONE))).containsExactly(slot(THU_0900), slot(THU_0900.plusSeconds(1800)), slot(THU_1030.minusSeconds(1800)),
                slot(THU_1030), slot(THU_1030.plusSeconds(1800)), slot(THU_1030.plusSeconds(3600)));

        tap(slot(THU_1030));
        assertThat(state()).isEqualTo(ConversationState.CONFIRM);
        assertThat(lastTo(PHONE)).isInstanceOfSatisfying(ListMessage.class, confirm -> {
            assertThat(confirm.body()).isEqualTo("موعد مع د. خوري يوم الخميس 15 تشرين الأول الساعة 10:30 ص.\nلمن هذا الموعد؟");
            assertThat(confirm.rows()).extracting(ListRow::id)
                    .containsExactly("confirm:me", "confirm:new", "confirm:other", "confirm:cancel");
        });

        tap("confirm:me");

        Patient rana = patients.findByClinicIdAndPhoneE164OrderById(clinic.getId(), PHONE).getFirst();
        assertThat(rana.getName()).isEqualTo("Rana");
        assertThat(rana.getLanguage()).isEqualTo(Language.AR);
        Appointment booked = appointments.findNextOpen(clinic.getId(), rana.getId(), clock.instant()).orElseThrow();
        assertThat(booked.getStartsAt()).isEqualTo(THU_1030);
        assertThat(booked.getDoctorId()).isEqualTo(khoury.getId());
        assertThat(booked.getSource()).isEqualTo(AppointmentSource.WHATSAPP);
        assertThat(reminders.findByClinicIdAndAppointmentIdOrderByDueAt(clinic.getId(), booked.getId())).hasSize(2);

        SentMessage done = lastSentTo(PHONE);
        assertThat(done.message()).isEqualTo(new TextMessage(PHONE,
                "تم الحجز باسم Rana: د. خوري يوم الخميس 15 تشرين الأول الساعة 10:30 ص. للإلغاء، راسلنا واختر «موعدي»."
                        + "\nأضِف الموعد إلى تقويمك: https://reminders.test/c/" + booked.getCalendarToken()));
        assertThat(logs.findByClinicIdAndWaMessageId(clinic.getId(), done.waMessageId()).orElseThrow().getAppointmentId())
                .isEqualTo(booked.getId());
        assertThat(whatsApp.recent()).as("menu, days, times, confirmation, booked").hasSize(5);
        assertThat(state()).isEqualTo(ConversationState.DONE);
        assertThat(flagged()).isFalse();
    }

    @Test
    void withSeveralDoctorsThePatientPicksAnActiveOne() {
        Doctor saab = data.doctor(clinic, "Dr. Saab", 30);
        data.weekly(saab, DayOfWeek.MONDAY, "09:00", "10:00");
        Doctor retired = data.doctor(clinic, "Dr. Retired", 30);
        retired.deactivate();
        doctors.save(retired);
        say("مرحبا");

        tap("menu:book");
        assertThat(state()).isEqualTo(ConversationState.CHOOSE_DOCTOR);
        assertThat(rowIds(lastTo(PHONE))).containsExactlyInAnyOrder("doctor:" + khoury.getId(), "doctor:" + saab.getId());

        tap("doctor:" + saab.getId());

        assertThat(state()).isEqualTo(ConversationState.CHOOSE_DAY);
        assertThat(rowIds(lastTo(PHONE))).as("Mondays within the next 30 days")
                .containsExactly("day:2026-10-19", "day:2026-10-26", "day:2026-11-02", "day:2026-11-09");
    }

    @Test
    void aBusyDayIsShownNineTimesAtATime() {
        data.hoursOn(khoury, THURSDAY, "08:00", "16:00");
        startBooking();

        tap("day:2026-10-15");
        List<String> first = rowIds(lastTo(PHONE));
        assertThat(first).hasSize(10).startsWith(slot(Instant.parse("2026-10-15T05:00:00Z"))).endsWith("slots:more");

        tap("slots:more");
        assertThat(rowIds(lastTo(PHONE))).as("12:30 to 15:30, and no more")
                .hasSize(7).startsWith(slot(Instant.parse("2026-10-15T09:30:00Z"))).doesNotContain("slots:more");
    }

    @Test
    void anotherTimeGoesBackToTheDaysTimesAndCancelBooksNothing() {
        reachConfirmation(PHONE);

        tap("confirm:other");
        assertThat(state()).isEqualTo(ConversationState.CHOOSE_SLOT);
        assertThat(rowIds(lastTo(PHONE))).contains(slot(THU_1030));

        tap(slot(THU_0900));
        tap("confirm:cancel");

        assertThat(lastTo(PHONE)).isEqualTo(new TextMessage(PHONE, "لا مشكلة، لم يتم حجز أي موعد."));
        assertThat(state()).isEqualTo(ConversationState.DONE);
        assertThat(activeAppointmentsAt(THU_0900)).isZero();
    }

    @Test
    void theBookingsEarlierListsStillWork() {
        startBooking();
        SentMessage daysList = lastSentTo(PHONE);
        tap("day:2026-10-15");
        SentMessage thursdayTimes = lastSentTo(PHONE);
        tap(slot(THU_1030));

        tapOn(daysList, "day:2026-10-16");
        assertThat(state()).isEqualTo(ConversationState.CHOOSE_SLOT);
        assertThat(rowIds(lastTo(PHONE))).contains(slot(Instant.parse("2026-10-16T06:00:00Z")));

        tapOn(thursdayTimes, slot(THU_0900));
        assertThat(state()).isEqualTo(ConversationState.CONFIRM);
        tap("confirm:me");

        assertThat(activeAppointmentsAt(THU_0900)).isEqualTo(1);
    }

    @Test
    void aStaleTapResendsTheCurrentStep() {
        reachConfirmation(PHONE);
        SentMessage confirmation = lastSentTo(PHONE);
        tap("confirm:other");

        tapOn(confirmation, "confirm:me");

        assertThat(state()).isEqualTo(ConversationState.CHOOSE_SLOT);
        assertThat(rowIds(lastTo(PHONE))).contains(slot(THU_1030));
        assertThat(activeAppointmentsAt(THU_1030)).isZero();
    }

    @Test
    void aSecondTapOnConfirmBooksOnceAndGetsNoReply() {
        reachConfirmation(PHONE);
        SentMessage confirmation = lastSentTo(PHONE);

        tapOn(confirmation, "confirm:me");
        tapOn(confirmation, "confirm:me");

        assertThat(activeAppointmentsAt(THU_1030)).isEqualTo(1);
        assertThat(whatsApp.recent()).hasSize(5);
    }

    @Test
    void aTimeTakenWhileThePatientWasConfirmingOffersTheRemainingTimes() {
        reachConfirmation(PHONE);
        Patient walkIn = data.patient(clinic, OTHER_PHONE);
        appointmentService.book(clinic.getId(), khoury.getId(), walkIn.getId(), THU_1030, AppointmentSource.MANUAL);

        tap("confirm:me");

        assertThat(lastTo(PHONE)).isInstanceOfSatisfying(ListMessage.class, times -> {
            assertThat(times.body()).isEqualTo(TAKEN_AR);
            assertThat(times.rows()).extracting(ListRow::id).hasSize(5).doesNotContain(slot(THU_1030));
        });
        assertThat(state()).isEqualTo(ConversationState.CHOOSE_SLOT);
        assertThat(patients.findByClinicIdAndPhoneE164OrderById(clinic.getId(), PHONE)).isEmpty();
    }

    @Test
    void aTimeTakenBeforeThePatientPickedItSaysSo() {
        startBooking();
        tap("day:2026-10-15");
        appointmentService.book(clinic.getId(), khoury.getId(), data.patient(clinic, OTHER_PHONE).getId(), THU_1030,
                AppointmentSource.MANUAL);

        tap(slot(THU_1030));

        assertThat(lastTo(PHONE)).isInstanceOfSatisfying(ListMessage.class,
                times -> assertThat(times.body()).isEqualTo(TAKEN_AR));
    }

    @Test
    void aDayThatFilledUpOffersTheOtherDays() {
        startBooking();
        data.closedOn(khoury, THURSDAY);

        tap("day:2026-10-15");

        assertThat(state()).isEqualTo(ConversationState.CHOOSE_DAY);
        assertThat(lastTo(PHONE)).isInstanceOfSatisfying(ListMessage.class, days -> {
            assertThat(days.body()).isEqualTo("امتلأ هذا اليوم للتو. اختر يومًا آخر.");
            assertThat(days.rows()).extracting(ListRow::id).doesNotContain("day:2026-10-15").hasSize(7);
        });
    }

    @Test
    void withNoFreeDayInTheNext30DaysTheClinicWillCall() {
        jdbc.update("delete from schedule_rule");
        data.hoursOn(khoury, THURSDAY.plusDays(35), "09:00", "12:00");
        say("مرحبا");

        tap("menu:book");

        assertThat(lastTo(PHONE)).isEqualTo(new TextMessage(PHONE, "لا أوقات متاحة خلال الثلاثين يومًا القادمة. سنتصل بك."));
        assertThat(state()).isEqualTo(ConversationState.DONE);
        assertThat(flagged()).isTrue();
    }

    @Test
    void withNoActiveDoctorTheClinicWillCall() {
        khoury.deactivate();
        doctors.save(khoury);
        say("مرحبا");

        tap("menu:book");

        assertThat(lastTo(PHONE)).isEqualTo(new TextMessage(PHONE, "الحجز عبر واتساب غير متاح حاليًا. سنتصل بك."));
        assertThat(flagged()).isTrue();
    }

    // --- Families: one number, several patients ---

    @Test
    void aFamilyPicksWhoTheAppointmentIsFor() {
        Patient mira = data.patient(clinic, "Mira", PHONE, Language.AR);
        Patient karim = data.patient(clinic, "Karim", PHONE, Language.AR);
        reachConfirmation(PHONE);
        assertThat(rowIds(lastTo(PHONE))).as("no WhatsApp name: the number has its patients")
                .containsExactly("confirm:patient:" + mira.getId(), "confirm:patient:" + karim.getId(), "confirm:new",
                        "confirm:other", "confirm:cancel");

        tap("confirm:patient:" + karim.getId());

        Appointment booked = appointments.findNextOpen(clinic.getId(), karim.getId(), clock.instant()).orElseThrow();
        assertThat(booked.getStartsAt()).isEqualTo(THU_1030);
        assertThat(lastTo(PHONE)).isInstanceOfSatisfying(TextMessage.class,
                done -> assertThat(done.body()).startsWith("تم الحجز باسم Karim: د. خوري"));
        assertThat(appointments.findNextOpen(clinic.getId(), mira.getId(), clock.instant())).isEmpty();
    }

    @Test
    void someoneElseTypesTheirNameAndJoinsTheNumbersFamily() {
        Patient mira = data.patient(clinic, "Mira", PHONE, Language.AR);
        reachConfirmation(PHONE);

        tap("confirm:new");
        assertThat(state()).isEqualTo(ConversationState.ENTER_NAME);
        assertThat(lastTo(PHONE)).isEqualTo(new TextMessage(PHONE, "ما الاسم الكامل للمريض؟ اكتبه من فضلك."));

        say("  Lina   Haddad ");

        List<Patient> family = patients.findByClinicIdAndPhoneE164OrderById(clinic.getId(), PHONE);
        assertThat(family).extracting(Patient::getName).containsExactly("Mira", "Lina Haddad");
        Patient lina = family.get(1);
        assertThat(lina.getLanguage()).isEqualTo(Language.AR);
        assertThat(appointments.findNextOpen(clinic.getId(), lina.getId(), clock.instant()).orElseThrow().getStartsAt())
                .isEqualTo(THU_1030);
        assertThat(lastTo(PHONE)).isInstanceOfSatisfying(TextMessage.class,
                done -> assertThat(done.body()).startsWith("تم الحجز باسم Lina Haddad:"));
        assertThat(whatsApp.recent()).as("menu, days, times, who, name question, booked").hasSize(6);
        assertThat(appointments.findNextOpen(clinic.getId(), mira.getId(), clock.instant())).isEmpty();
        assertThat(flagged()).isFalse();
    }

    @Test
    void aNameAlreadyOnTheNumberIsThatPatient() {
        Patient mira = data.patient(clinic, "Mira Haddad", PHONE, Language.AR);
        reachConfirmation(PHONE);
        tap("confirm:new");

        say("mira haddad");

        assertThat(patients.findByClinicIdAndPhoneE164OrderById(clinic.getId(), PHONE)).hasSize(1);
        assertThat(appointments.findNextOpen(clinic.getId(), mira.getId(), clock.instant())).isPresent();
    }

    @Test
    void whileWaitingForTheNameTheChoiceCanStillChange() {
        reachConfirmation(PHONE);
        SentMessage who = lastSentTo(PHONE);
        tap("confirm:new");

        tapOn(who, "confirm:me");

        assertThat(activeAppointmentsAt(THU_1030)).isEqualTo(1);
        assertThat(patients.findByClinicIdAndPhoneE164OrderById(clinic.getId(), PHONE)).extracting(Patient::getName)
                .as("for them after all, under their WhatsApp name").containsExactly("Rana");
    }

    @Test
    void anythingButANameGetsTheMenuOnceAndFlagsTheConversation() {
        reachConfirmation(PHONE);
        tap("confirm:new");

        say("😊");

        assertThat(lastTo(PHONE)).isInstanceOfSatisfying(ReplyButtonsMessage.class,
                menu -> assertThat(menu.body()).isEqualTo(MENU_AR));
        assertThat(flagged()).isTrue();
        assertThat(activeAppointmentsAt(THU_1030)).isZero();
    }

    @Test
    void myAppointmentListsTheWholeFamilysAndCancelsTheOnePicked() {
        Patient mira = data.patient(clinic, "Mira", PHONE, Language.AR);
        Patient karim = data.patient(clinic, "Karim", PHONE, Language.AR);
        Appointment miras = appointmentService.book(clinic.getId(), khoury.getId(), mira.getId(), THU_1030,
                AppointmentSource.MANUAL);
        Appointment karims = appointmentService.book(clinic.getId(), khoury.getId(), karim.getId(), THU_0900,
                AppointmentSource.MANUAL);
        say("مرحبا");

        tap("menu:mine");
        assertThat(state()).isEqualTo(ConversationState.MY_APPOINTMENT);
        assertThat(lastTo(PHONE)).isInstanceOfSatisfying(ListMessage.class, list -> {
            assertThat(list.rows()).extracting(ListRow::id).as("earliest first")
                    .containsExactly("mine:show:" + karims.getId(), "mine:show:" + miras.getId());
            assertThat(list.rows().getFirst().description()).isEqualTo("9:00 ص · د. خوري · Karim");
        });

        tap("mine:show:" + miras.getId());
        assertThat(lastTo(PHONE)).isInstanceOfSatisfying(ReplyButtonsMessage.class, mine -> {
            assertThat(mine.body()).isEqualTo("الموعد القادم باسم Mira: د. خوري يوم الخميس 15 تشرين الأول الساعة 10:30 ص.");
            assertThat(mine.buttons()).extracting(ReplyButton::id).containsExactly("mine:cancel:" + miras.getId(), "mine:keep");
        });

        tap("mine:cancel:" + miras.getId());

        assertThat(appointments.findByIdAndClinicId(miras.getId(), clinic.getId()).orElseThrow().getStatus())
                .isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(appointments.findByIdAndClinicId(karims.getId(), clinic.getId()).orElseThrow().getStatus())
                .isEqualTo(AppointmentStatus.BOOKED);
    }

    @Test
    void oneOptedOutFamilyMemberMeansTheNumberGetsNoReply() {
        data.patient(clinic, "Mira", PHONE, Language.AR);
        Patient karim = data.patient(clinic, "Karim", PHONE, Language.AR);
        karim.optOut();
        patients.save(karim);

        say("مرحبا");

        assertThat(whatsApp.recent()).isEmpty();
        assertThat(flagged()).isTrue();
    }

    // --- My appointment and Info ---

    @Test
    void myAppointmentShowsTheNextOneAndCancelsIt() {
        Patient rana = data.patient(clinic, "Rana", PHONE, Language.AR);
        Appointment appointment = appointmentService.book(clinic.getId(), khoury.getId(), rana.getId(), THU_1030,
                AppointmentSource.MANUAL);
        say("مرحبا");

        tap("menu:mine");
        assertThat(state()).isEqualTo(ConversationState.MY_APPOINTMENT);
        assertThat(lastTo(PHONE)).isInstanceOfSatisfying(ReplyButtonsMessage.class, mine -> {
            assertThat(mine.body()).isEqualTo("موعدك القادم: د. خوري يوم الخميس 15 تشرين الأول الساعة 10:30 ص.");
            assertThat(mine.buttons()).extracting(ReplyButton::id)
                    .containsExactly("mine:cancel:" + appointment.getId(), "mine:keep");
        });

        tap("mine:cancel:" + appointment.getId());

        Appointment cancelled = appointments.findByIdAndClinicId(appointment.getId(), clinic.getId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(cancelled.getCancelledBy()).isEqualTo(CancelledBy.PATIENT);
        assertThat(lastTo(PHONE)).isInstanceOfSatisfying(TextMessage.class,
                ack -> assertThat(ack.body()).startsWith("تم إلغاء موعدك مع د. خوري"));
        assertThat(state()).isEqualTo(ConversationState.DONE);
    }

    @Test
    void keepingTheAppointmentSendsNothing() {
        Patient rana = data.patient(clinic, "Rana", PHONE, Language.AR);
        Appointment appointment = appointmentService.book(clinic.getId(), khoury.getId(), rana.getId(), THU_1030,
                AppointmentSource.MANUAL);
        say("مرحبا");
        tap("menu:mine");

        tap("mine:keep");

        assertThat(whatsApp.recent()).hasSize(2);
        assertThat(state()).isEqualTo(ConversationState.DONE);
        assertThat(appointments.findByIdAndClinicId(appointment.getId(), clinic.getId()).orElseThrow().getStatus())
                .isEqualTo(AppointmentStatus.BOOKED);
    }

    @Test
    void anAppointmentCancelledMeanwhileIsNotCancelledAgain() {
        Patient rana = data.patient(clinic, "Rana", PHONE, Language.AR);
        Appointment appointment = appointmentService.book(clinic.getId(), khoury.getId(), rana.getId(), THU_1030,
                AppointmentSource.MANUAL);
        say("مرحبا");
        tap("menu:mine");
        appointmentService.cancel(clinic.getId(), appointment.getId(), CancelledBy.STAFF);

        tap("mine:cancel:" + appointment.getId());

        assertThat(lastTo(PHONE)).isInstanceOfSatisfying(ReplyButtonsMessage.class,
                menu -> assertThat(menu.body()).isEqualTo("لا مواعيد قادمة لديك. كيف يمكننا مساعدتك؟"));
        assertThat(appointments.findByIdAndClinicId(appointment.getId(), clinic.getId()).orElseThrow().getCancelledBy())
                .isEqualTo(CancelledBy.STAFF);
    }

    @Test
    void withoutAnUpcomingAppointmentTheMenuComesBack() {
        say("Hello");

        tap("menu:mine");

        assertThat(state()).isEqualTo(ConversationState.MENU);
        assertThat(lastTo(PHONE)).isInstanceOfSatisfying(ReplyButtonsMessage.class,
                menu -> assertThat(menu.body()).isEqualTo("لا مواعيد قادمة لديك. كيف يمكننا مساعدتك؟"));
    }

    @Test
    void infoSendsTheAddressAndHoursAndTheMenuStillWorks() {
        clinic.changeContactDetails("شارع البرازيل، زحلة", "من الاثنين إلى السبت، 9 - 12");
        clinics.save(clinic);
        say("مرحبا");
        SentMessage menu = lastSentTo(PHONE);

        tap("menu:info");
        assertThat(lastTo(PHONE)).isEqualTo(new TextMessage(PHONE,
                "العنوان: شارع البرازيل، زحلة\nأوقات الدوام: من الاثنين إلى السبت، 9 - 12"));
        assertThat(state()).isEqualTo(ConversationState.INFO);

        tapOn(menu, "menu:book");
        assertThat(state()).isEqualTo(ConversationState.CHOOSE_DAY);
    }

    // --- Expiry, typing, opt-outs and hidden numbers ---

    @Test
    void anIdleConversationStartsAgainAtTheMenuAfter30Minutes() {
        startBooking();
        clock.setInstant(clock.instant().plus(Duration.ofMinutes(29)));
        tap("day:2026-10-15");
        assertThat(state()).isEqualTo(ConversationState.CHOOSE_SLOT);

        clock.setInstant(clock.instant().plus(Duration.ofMinutes(30)));
        tap(slot(THU_1030));

        assertThat(state()).isEqualTo(ConversationState.MENU);
        assertThat(lastTo(PHONE)).isInstanceOfSatisfying(ReplyButtonsMessage.class,
                menu -> assertThat(menu.body()).isEqualTo(MENU_AR));
        assertThat(flagged()).isFalse();
    }

    @Test
    void typingInsteadOfTappingGetsTheMenuOnceAndFlagsTheConversation() {
        startBooking();

        say("بدي موعد بكرا الساعة 5");
        assertThat(state()).isEqualTo(ConversationState.MENU);
        assertThat(flagged()).isTrue();
        assertThat(whatsApp.recent()).hasSize(3);

        say("??");
        assertThat(whatsApp.recent()).as("no second menu").hasSize(3);

        tap("menu:book");
        assertThat(state()).isEqualTo(ConversationState.CHOOSE_DAY);
    }

    @Test
    void thanksAfterABookingIsIgnoredUntilTheSessionEnds() {
        reachConfirmation(PHONE);
        tap("confirm:me");

        say("شكراً");
        assertThat(whatsApp.recent()).hasSize(5);
        assertThat(flagged()).isFalse();
        assertThat(state()).isEqualTo(ConversationState.DONE);

        clock.setInstant(clock.instant().plus(Duration.ofMinutes(31)));
        say("مرحبا");
        assertThat(lastTo(PHONE)).isInstanceOfSatisfying(ReplyButtonsMessage.class,
                menu -> assertThat(menu.body()).isEqualTo(MENU_AR));
    }

    @Test
    void voiceNotesAndPhotosCountAsTyping() {
        say("مرحبا");

        deliver(SimulatedWebhooks.text("pn-a", PHONE, "Rana", SimulatedWebhooks.newMessageId(), "x", clock.instant())
                .replace("\"type\":\"text\",\"text\":{\"body\":\"x\"}", "\"type\":\"audio\",\"audio\":{\"id\":\"1234\"}"));

        assertThat(jdbc.queryForObject("select count(*) from message_log where kind = 'OTHER'", Integer.class)).isEqualTo(1);
        assertThat(whatsApp.recent()).hasSize(2);
        assertThat(flagged()).isTrue();
    }

    @Test
    void anOptedOutPatientGetsNoReplyAndIsFlaggedForTheSecretary() {
        Patient rana = data.patient(clinic, "Rana", PHONE, Language.AR);
        rana.optOut();
        patients.save(rana);

        say("مرحبا، بدي موعد");

        assertThat(whatsApp.recent()).isEmpty();
        assertThat(flagged()).isTrue();
    }

    @Test
    void aSenderWhoseNumberMetaHidesIsNotAnswered() throws IOException {
        deliver(fixture("hidden-number.json")
                .replace("106540352242922", "pn-a")
                .replace("\"body\": \"STOP\"", "\"body\": \"hello\""));

        assertThat(whatsApp.recent()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from conversation", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from webhook_inbox", Integer.class)).isZero();
    }

    @Test
    void reminderButtonTapsAreLeftToTheReminders() {
        deliver(SimulatedWebhooks.quickReply("pn-a", PHONE, "Rana", SimulatedWebhooks.newMessageId(), "wamid.REMINDER",
                "تأكيد", clock.instant()));

        assertThat(whatsApp.recent()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from conversation", Integer.class)).isZero();
    }

    // --- Double booking (SPEC section 8) ---

    @Test
    void twoPatientsConfirmingTheSameTimeTogetherGetOneBooking() throws Exception {
        reachConfirmation(PHONE);
        reachConfirmation(OTHER_PHONE);
        storeTap(PHONE, lastSentTo(PHONE), "confirm:me", "Rana");
        storeTap(OTHER_PHONE, lastSentTo(OTHER_PHONE), "confirm:me", "Rana");

        CyclicBarrier start = new CyclicBarrier(2);
        Callable<Integer> drain = () -> {
            start.await(10, SECONDS);
            return processor.drain();
        };
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            for (Future<Integer> worker : workers.invokeAll(List.of(drain, drain))) {
                worker.get(30, SECONDS);
            }
        } finally {
            workers.shutdownNow();
        }

        assertThat(activeAppointmentsAt(THU_1030)).isEqualTo(1);
        List<OutboundMessage> answers = List.of(lastTo(PHONE), lastTo(OTHER_PHONE));
        assertThat(answers).filteredOn(TextMessage.class::isInstance).singleElement()
                .satisfies(booked -> assertThat(((TextMessage) booked).body()).startsWith("تم الحجز"));
        assertThat(answers).filteredOn(ListMessage.class::isInstance).singleElement()
                .satisfies(offer -> assertThat(((ListMessage) offer).body()).isEqualTo(TAKEN_AR));
        assertThat(jdbc.queryForObject("select count(*) from webhook_inbox", Integer.class)).isZero();
    }

    @Test
    void losingTheRaceAtTheInsertItselfOffersTheRemainingTimes() throws Exception {
        reachConfirmation(PHONE);
        Patient walkIn = data.patient(clinic, OTHER_PHONE);
        CountDownLatch inserted = new CountDownLatch(1);
        // The secretary books the same time and holds the transaction open until the WhatsApp
        // booking is blocked on it: the flow's own check saw the time as free.
        CompletableFuture<Void> secretary = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    insert into appointment (clinic_id, doctor_id, patient_id, starts_at, ends_at, status, source)
                    values (?, ?, ?, ?, ?, 'BOOKED', 'MANUAL')""", clinic.getId(), khoury.getId(), walkIn.getId(),
                    THU_1030.atOffset(ZoneOffset.UTC), THU_1030.plusSeconds(1800).atOffset(ZoneOffset.UTC));
            inserted.countDown();
            awaitAnotherSessionWaitingForALock();
        }));
        assertThat(inserted.await(10, SECONDS)).isTrue();

        tap("confirm:me");
        secretary.get(10, SECONDS);

        assertThat(lastTo(PHONE)).isInstanceOfSatisfying(ListMessage.class,
                times -> assertThat(times.body()).isEqualTo(TAKEN_AR));
        assertThat(activeAppointmentsAt(THU_1030)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from appointment where patient_id <> ?", Integer.class,
                walkIn.getId())).isZero();
        assertThat(state()).isEqualTo(ConversationState.CHOOSE_SLOT);
        assertThat(jdbc.queryForObject("select count(*) from webhook_inbox", Integer.class)).as("not retried").isZero();
    }

    // --- Clinic isolation ---

    @Test
    void theSameNumberHasASeparateConversationWithEachClinic() {
        Clinic other = data.connectedClinic("B", "pn-b");
        Doctor theirDoctor = data.doctor(other, "Dr. B", 30);
        Patient theirPatient = data.patient(other, "Rana", PHONE, Language.EN);
        Appointment theirs = appointmentService.book(other.getId(), theirDoctor.getId(), theirPatient.getId(), THU_1030,
                AppointmentSource.MANUAL);

        sayTo("pn-b", PHONE, "مرحبا");
        say("مرحبا");
        assertThat(lastTo(PHONE)).isInstanceOfSatisfying(ReplyButtonsMessage.class,
                menu -> assertThat(menu.body()).isEqualTo(MENU_AR));
        assertThat(jdbc.queryForList("select clinic_id from conversation order by clinic_id", Long.class))
                .containsExactly(clinic.getId(), other.getId());

        tap("menu:mine");
        assertThat(lastTo(PHONE)).as("clinic B's appointment is not clinic A's")
                .isInstanceOfSatisfying(ReplyButtonsMessage.class,
                        menu -> assertThat(menu.body()).isEqualTo("لا مواعيد قادمة لديك. كيف يمكننا مساعدتك؟"));

        tap("menu:book");
        storeTap(PHONE, lastSentTo(PHONE), "doctor:" + theirDoctor.getId(), "Dr. B");
        processor.drain();
        assertThat(rowIds(lastTo(PHONE))).as("still clinic A's doctor").startsWith("day:2026-10-15");
        assertThat(BookingContext.fromJson(contextJson()).doctorId()).isEqualTo(khoury.getId());
        assertThat(appointments.findByIdAndClinicId(theirs.getId(), other.getId()).orElseThrow().getStatus())
                .isEqualTo(AppointmentStatus.BOOKED);
    }

    // --- helpers ---

    private void startBooking() {
        say("مرحبا");
        tap("menu:book");
    }

    private void reachConfirmation(String phone) {
        say(phone, "مرحبا");
        tap(phone, "menu:book");
        tap(phone, "day:2026-10-15");
        tap(phone, slot(THU_1030));
        assertThat(lastTo(phone)).isInstanceOf(ListMessage.class);
    }

    private void say(String text) {
        say(PHONE, text);
    }

    private void say(String phone, String text) {
        sayTo("pn-a", phone, text);
    }

    private void sayTo(String phoneNumberId, String phone, String text) {
        deliver(SimulatedWebhooks.text(phoneNumberId, phone, "Rana", SimulatedWebhooks.newMessageId(), text, clock.instant()));
    }

    private void tap(String id) {
        tap(PHONE, id);
    }

    private void tap(String phone, String id) {
        tapOn(lastSentTo(phone), id);
    }

    /** Taps a button or row of an earlier message, as a patient scrolling up would. */
    private void tapOn(SentMessage sent, String id) {
        String title = switch (sent.message()) {
            case ReplyButtonsMessage buttons -> buttons.buttons().stream().filter(b -> b.id().equals(id))
                    .map(ReplyButton::title).findFirst().orElseThrow(() -> new AssertionError(id + " is not on " + sent));
            case ListMessage list -> list.rows().stream().filter(r -> r.id().equals(id))
                    .map(ListRow::title).findFirst().orElseThrow(() -> new AssertionError(id + " is not on " + sent));
            default -> throw new AssertionError("Nothing to tap on " + sent);
        };
        storeTap(sent.message().to(), sent, id, title);
        processor.drain();
    }

    private void storeTap(String phone, SentMessage repliedTo, String id, String title) {
        String messageId = SimulatedWebhooks.newMessageId();
        inbox.store(repliedTo.message() instanceof ListMessage
                ? SimulatedWebhooks.listReply(repliedTo.phoneNumberId(), phone, "Rana", messageId, repliedTo.waMessageId(),
                id, title, clock.instant())
                : SimulatedWebhooks.buttonReply(repliedTo.phoneNumberId(), phone, "Rana", messageId,
                repliedTo.waMessageId(), id, title, clock.instant()));
    }

    private void deliver(String payload) {
        inbox.store(payload);
        processor.drain();
    }

    private SentMessage lastSentTo(String phone) {
        return whatsApp.recent().stream().filter(sent -> sent.message().to().equals(phone)).findFirst()
                .orElseThrow(() -> new AssertionError("Nothing was sent to " + phone));
    }

    private OutboundMessage lastTo(String phone) {
        return lastSentTo(phone).message();
    }

    private static List<String> rowIds(OutboundMessage message) {
        assertThat(message).isInstanceOf(ListMessage.class);
        return ((ListMessage) message).rows().stream().map(ListRow::id).toList();
    }

    private static String slot(Instant start) {
        return "slot:" + start.getEpochSecond();
    }

    private ConversationState state() {
        return ConversationState.valueOf(jdbc.queryForObject(
                "select state from conversation where clinic_id = ? and patient_phone = ?", String.class, clinic.getId(), PHONE));
    }

    private boolean flagged() {
        return jdbc.queryForObject("select needs_human from conversation where clinic_id = ? and patient_phone = ?",
                Boolean.class, clinic.getId(), PHONE);
    }

    private String contextJson() {
        return jdbc.queryForObject("select context_json::text from conversation where clinic_id = ? and patient_phone = ?",
                String.class, clinic.getId(), PHONE);
    }

    private int activeAppointmentsAt(Instant start) {
        return jdbc.queryForObject("select count(*) from appointment where starts_at = ? and status <> 'CANCELLED'",
                Integer.class, start.atOffset(ZoneOffset.UTC));
    }

    private void awaitAnotherSessionWaitingForALock() {
        long deadline = System.nanoTime() + SECONDS.toNanos(10);
        while (jdbc.queryForObject("""
                select count(*) from pg_stat_activity
                where datname = current_database() and wait_event_type = 'Lock'""", Integer.class) == 0) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("The WhatsApp booking never waited for the secretary's insert");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = BookingFlowIT.class.getResourceAsStream("/whatsapp/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
