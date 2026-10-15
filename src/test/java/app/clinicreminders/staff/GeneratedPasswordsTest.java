package app.clinicreminders.staff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class GeneratedPasswordsTest {

    @Test
    void fourGroupsOfFourWithoutLookAlikes() {
        for (int i = 0; i < 200; i++) {
            assertThat(GeneratedPasswords.generate())
                    .matches("[a-zA-Z2-9]{4}(-[a-zA-Z2-9]{4}){3}")
                    .doesNotContainAnyWhitespaces()
                    .doesNotContain("0", "O", "1", "l", "I");
        }
    }

    @Test
    void neverRepeats() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertThat(seen.add(GeneratedPasswords.generate())).isTrue();
        }
    }
}
