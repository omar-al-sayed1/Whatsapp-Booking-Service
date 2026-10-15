package app.clinicreminders.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.clinicreminders.common.Language;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.whatsapp.OutboundMessage.ListMessage;
import app.clinicreminders.whatsapp.OutboundMessage.ListRow;
import app.clinicreminders.whatsapp.OutboundMessage.ReplyButton;
import app.clinicreminders.whatsapp.OutboundMessage.ReplyButtonsMessage;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.util.ReflectionTestUtils;

class BookingMessagesTest {

    private static final String TO = "+96170123456";
    private static final ZoneId BEIRUT = ZoneId.of("Asia/Beirut");
    private static final LocalDate TUESDAY = LocalDate.of(2026, 11, 3);
    /** Tuesday 3 November 2026, 10:30 in Beirut. */
    private static final Instant TUE_1030 = Instant.parse("2026-11-03T08:30:00Z");

    private final BookingMessages messages = new BookingMessages(messageSource());

    @Test
    void theMenuInBothLanguages() {
        ReplyButtonsMessage arabic = messages.menu(TO, Language.AR, false);
        assertThat(arabic.body()).isEqualTo("مرحبًا! كيف يمكننا مساعدتك؟");
        assertThat(arabic.buttons()).containsExactly(new ReplyButton("menu:book", "حجز موعد"),
                new ReplyButton("menu:mine", "موعدي"), new ReplyButton("menu:info", "معلومات"));

        ReplyButtonsMessage english = messages.menu(TO, Language.EN, true);
        assertThat(english.body()).isEqualTo("You have no upcoming appointments. How can we help?");
        assertThat(english.buttons()).extracting(ReplyButton::title).containsExactly("Book", "My appointment", "Info");
    }

    @Test
    void doctorsAreListedByIdWithLongNamesCut() {
        Doctor khoury = doctor(7, "Dr. Khoury");
        Doctor longName = doctor(8, "Dr. Marie-Thérèse Abou Jaoudeh");

        ListMessage list = messages.doctors(TO, Language.EN, List.of(khoury, longName));

        assertThat(list.rows()).containsExactly(new ListRow("doctor:7", "Dr. Khoury", null),
                new ListRow("doctor:8", "Dr. Marie-Thérèse Abou…", null));
    }

    @Test
    void daysShowTheirFreeTimesWithWesternDigits() {
        Map<LocalDate, Integer> days = new LinkedHashMap<>();
        days.put(TUESDAY, 6);
        days.put(TUESDAY.plusDays(1), 12);

        ListMessage arabic = messages.days(TO, Language.AR, days, false);

        assertThat(arabic.body()).isEqualTo("اختر يومًا.");
        assertThat(arabic.button()).isEqualTo("الأيام");
        assertThat(arabic.rows()).containsExactly(
                new ListRow("day:2026-11-03", "الثلاثاء 3 تشرين الثاني", "أوقات متاحة: 6"),
                new ListRow("day:2026-11-04", "الأربعاء 4 تشرين الثاني", "أوقات متاحة: 12"));
        assertThat(messages.days(TO, Language.EN, days, true).body()).isEqualTo("That day has just filled up. Pick another day.");
    }

    @Test
    void everyDateOfTheYearFitsARowTitleInBothLanguages() {
        for (Language language : Language.values()) {
            for (LocalDate day = LocalDate.of(2026, 1, 1); day.getYear() == 2026; day = day.plusDays(7)) {
                Map<LocalDate, Integer> week = new LinkedHashMap<>();
                for (int i = 0; i < 7; i++) {
                    week.put(day.plusDays(i), 1);
                }
                assertThat(messages.days(TO, language, week, false).rows())
                        .extracting(ListRow::title)
                        .allSatisfy(title -> assertThat(title).doesNotEndWith("…"));
            }
        }
    }

    @Test
    void timesAreRowsWithTheirStartAndALaterTimesRow() {
        List<Instant> times = List.of(TUE_1030, TUE_1030.plusSeconds(1800));

        ListMessage english = messages.times(TO, Language.EN, TUESDAY, times, true, BEIRUT, false);

        assertThat(english.body()).isEqualTo("Pick a time on Tuesday 3 November.");
        assertThat(english.rows()).containsExactly(
                new ListRow("slot:" + TUE_1030.getEpochSecond(), "10:30 AM", null),
                new ListRow("slot:" + TUE_1030.plusSeconds(1800).getEpochSecond(), "11:00 AM", null),
                new ListRow("slots:more", "Later times", null));

        ListMessage arabic = messages.times(TO, Language.AR, TUESDAY, times, false, BEIRUT, true);
        assertThat(arabic.body()).isEqualTo("عذرًا، حُجز هذا الوقت للتو. اختر وقتًا آخر يوم الثلاثاء 3 تشرين الثاني.");
        assertThat(arabic.rows()).extracting(ListRow::title).containsExactly("10:30 ص", "11:00 ص");
    }

    @Test
    void timesAreInTheClinicsZoneAfterTheAutumnChange() {
        // Lebanon goes back to UTC+2 at midnight on Sunday 25 October 2026.
        LocalDate monday = LocalDate.of(2026, 10, 26);
        Instant nineAm = Instant.parse("2026-10-26T07:00:00Z");

        assertThat(messages.times(TO, Language.EN, monday, List.of(nineAm), false, BEIRUT, false).rows())
                .extracting(ListRow::title).containsExactly("9:00 AM");
    }

    @Test
    void confirmationAndBooked() {
        ReplyButtonsMessage confirm = messages.confirm(TO, Language.AR, "د. خوري", TUE_1030, BEIRUT);

        assertThat(confirm.body()).isEqualTo("هل نحجز لك موعدًا مع د. خوري يوم الثلاثاء 3 تشرين الثاني الساعة 10:30 ص؟");
        assertThat(confirm.buttons()).containsExactly(new ReplyButton("confirm:yes", "تأكيد"),
                new ReplyButton("confirm:other", "وقت آخر"), new ReplyButton("confirm:cancel", "إلغاء"));
        assertThat(messages.booked(TO, Language.EN, "Dr. Khoury", TUE_1030, BEIRUT).body())
                .isEqualTo("Booked: Dr. Khoury on Tuesday 3 November at 10:30 AM. To cancel, message us and tap My appointment.");
    }

    @Test
    void myAppointmentCarriesTheAppointmentId() {
        ReplyButtonsMessage mine = messages.myAppointment(TO, Language.EN, "Dr. Khoury", TUE_1030, BEIRUT, 45);

        assertThat(mine.body()).isEqualTo("Your next appointment: Dr. Khoury on Tuesday 3 November at 10:30 AM.");
        assertThat(mine.buttons()).containsExactly(new ReplyButton("mine:cancel:45", "Cancel it"),
                new ReplyButton("mine:keep", "Keep it"));
    }

    @Test
    void infoShowsWhicheverDetailsTheClinicFilledIn() {
        assertThat(messages.info(TO, Language.EN, " Main street, Zahle ", "Mon-Sat 9-1").body())
                .isEqualTo("Address: Main street, Zahle\nOpening hours: Mon-Sat 9-1");
        assertThat(messages.info(TO, Language.AR, "شارع البرازيل، زحلة", null).body())
                .isEqualTo("العنوان: شارع البرازيل، زحلة");
        assertThat(messages.info(TO, Language.EN, " ", null).body())
                .isEqualTo("Please call us for our address and opening hours.");
    }

    @Test
    void shortTitlesAreLeftAlone() {
        assertThat(BookingMessages.fit("x".repeat(24))).isEqualTo("x".repeat(24));
        assertThat(BookingMessages.fit("x".repeat(25))).hasSize(24).endsWith("…");
    }

    private static Doctor doctor(long id, String name) {
        Doctor doctor = new Doctor(1, name, 30);
        ReflectionTestUtils.setField(doctor, "id", id);
        return doctor;
    }

    private static ResourceBundleMessageSource messageSource() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
