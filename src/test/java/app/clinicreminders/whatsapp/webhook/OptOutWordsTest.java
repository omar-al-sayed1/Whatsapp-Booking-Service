package app.clinicreminders.whatsapp.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OptOutWordsTest {

    @ParameterizedTest
    @ValueSource(strings = {"STOP", "stop", " Stop ", "STOP.", "stop!!", "🛑 STOP", "توقف", " توقف ", "توقّف", "تــوقف", "توقف!"})
    void recognisesStopInEitherLanguage(String text) {
        assertThat(OptOutWords.isOptOut(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"stop the reminders please", "don't stop", "stopped", "Hello", "", "توقفوا عن الرسائل", "ok"})
    void ignoresAnythingElse(String text) {
        assertThat(OptOutWords.isOptOut(text)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"توقف", "توقّف"})
    void knowsTheArabicWord(String text) {
        assertThat(OptOutWords.isArabic(text)).isTrue();
        assertThat(OptOutWords.isArabic("STOP")).isFalse();
    }
}
