package app.clinicreminders.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/** Every user-facing text exists in both languages, and the default bundle stays empty. */
class MessageBundlesTest {

    @Test
    void arabicAndEnglishHaveTheSameKeys() throws IOException {
        assertThat(load("messages_ar.properties").stringPropertyNames())
                .isNotEmpty()
                .containsExactlyInAnyOrderElementsOf(load("messages_en.properties").stringPropertyNames());
    }

    @Test
    void theDefaultBundleHasNoTexts() throws IOException {
        assertThat(load("messages.properties")).isEmpty();
    }

    private static Properties load(String name) throws IOException {
        try (Reader reader = new InputStreamReader(MessageBundlesTest.class.getResourceAsStream("/" + name),
                StandardCharsets.UTF_8)) {
            Properties properties = new Properties();
            properties.load(reader);
            return properties;
        }
    }
}
