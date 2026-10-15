package app.clinicreminders.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumbersTest {

    @ParameterizedTest
    @CsvSource({
            "03 123 456,         +9613123456",
            "03-123456,          +9613123456",
            "3123456,            +9613123456",
            "70 123 456,         +96170123456",
            "70123456,           +96170123456",
            "081 234 56,         +9618123456",
            "+961 70 123 456,    +96170123456",
            "00961 3 123456,     +9613123456",
            "961 70 123456,      +96170123456",
            "+33 6 12 34 56 78,  +33612345678",
            "(+961) 3/123456,    +9613123456"
    })
    void readsNumbersTheWayTheyAreWrittenInLebanon(String typed, String e164) {
        assertThat(PhoneNumbers.parseLebanese(typed)).isEqualTo(e164);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12", "03 123", "070 123 456 789", "phone", "+0961123456", "03 12a 456"})
    void rejectsWhatCannotBeAPhoneNumber(String typed) {
        assertThat(PhoneNumbers.parseLebanese(typed)).isNull();
    }
}
