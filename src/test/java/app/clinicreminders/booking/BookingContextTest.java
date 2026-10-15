package app.clinicreminders.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import app.clinicreminders.common.Language;
import org.junit.jupiter.api.Test;

class BookingContextTest {

    private static final LocalDate TUESDAY = LocalDate.of(2026, 11, 3);
    private static final Instant TUE_1030 = Instant.parse("2026-11-03T08:30:00Z");

    @Test
    void survivesTheRoundTripThroughJson() {
        BookingContext context = BookingContext.start(Language.AR).withDoctor(7).withDay(TUESDAY, 2).withSlot(TUE_1030);

        assertThat(BookingContext.fromJson(context.toJson())).isEqualTo(context);
        assertThat(context.toJson())
                .isEqualTo("{\"language\":\"ar\",\"doctorId\":7,\"day\":\"2026-11-03\",\"slot\":\"2026-11-03T08:30:00Z\",\"page\":2}");
    }

    @Test
    void storesOnlyWhatIsSet() {
        assertThat(BookingContext.start(Language.EN).toJson()).isEqualTo("{\"language\":\"en\"}");
        BookingContext shown = BookingContext.start(Language.EN).menuResentOnce().withAppointment(45);
        assertThat(BookingContext.fromJson(shown.toJson())).isEqualTo(shown);
    }

    @Test
    void anEmptyOrMissingContextHasNoLanguage() {
        BookingContext empty = new BookingContext(null, null, null, null, 0, false, null);

        assertThat(BookingContext.fromJson("{}")).isEqualTo(empty);
        assertThat(BookingContext.fromJson(null)).isEqualTo(empty);
        assertThat(BookingContext.fromJson(" ")).isEqualTo(empty);
    }

    @Test
    void pickingADoctorStartsTheDayAndTimeOver() {
        BookingContext picked = BookingContext.start(Language.AR).withDoctor(7).withDay(TUESDAY, 1).withSlot(TUE_1030);

        assertThat(picked.withDoctor(8)).isEqualTo(new BookingContext(Language.AR, 8L, null, null, 0, false, null));
        assertThat(picked.withDay(TUESDAY, 0).slot()).isNull();
    }

    @Test
    void theMenuResentFlagLastsForTheSession() {
        BookingContext resent = BookingContext.start(Language.AR).withDoctor(7).menuResentOnce();

        assertThat(resent).isEqualTo(new BookingContext(Language.AR, null, null, null, 0, true, null));
        assertThat(resent.withDoctor(7).withDay(TUESDAY, 0).cleared().menuResent()).isTrue();
    }
}
