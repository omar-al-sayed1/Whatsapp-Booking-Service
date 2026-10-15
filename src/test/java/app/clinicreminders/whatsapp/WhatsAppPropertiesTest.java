package app.clinicreminders.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.net.URI;
import java.util.Map;

import app.clinicreminders.whatsapp.WhatsAppProperties.ClientType;
import org.junit.jupiter.api.Test;

class WhatsAppPropertiesTest {

    private static final URI BASE = URI.create("https://graph.facebook.com");

    @Test
    void theRealClientNeedsEveryVariable() {
        assertThatIllegalStateException()
                .isThrownBy(() -> cloud("", "token", "v26.0", "key"))
                .withMessage("WA_APP_SECRET must be set");
        assertThatIllegalStateException()
                .isThrownBy(() -> cloud("secret", " ", "v26.0", "key"))
                .withMessage("WA_VERIFY_TOKEN must be set");
        assertThatIllegalStateException()
                .isThrownBy(() -> cloud("secret", "token", "v26.0", null))
                .withMessage("TOKEN_ENCRYPTION_KEY must be set");
        assertThatIllegalStateException()
                .isThrownBy(() -> cloud("secret", "token", "26", "key"))
                .withMessageStartingWith("WA_GRAPH_VERSION must look like v26.0");
    }

    @Test
    void theFakeClientNeedsOnlyTheWebhookSecrets() {
        var properties = new WhatsAppProperties(ClientType.FAKE, "secret", "token", null, null, BASE, null, null, null);

        assertThat(properties.inbox().maxAttempts()).isEqualTo(5);
        assertThat(properties.markets()).isEmpty();
    }

    @Test
    void theClientDefaultsToTheRealOne() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new WhatsAppProperties(null, "secret", "token", null, null, BASE, null, null, null))
                .withMessage("TOKEN_ENCRYPTION_KEY must be set");
    }

    @Test
    void toStringHidesSecrets() {
        String text = cloud("app-secret-value", "verify-token-value", "v26.0", "key-value").toString();

        assertThat(text).doesNotContain("app-secret-value", "verify-token-value", "key-value");
    }

    private static WhatsAppProperties cloud(String appSecret, String verifyToken, String version, String key) {
        return new WhatsAppProperties(ClientType.CLOUD, appSecret, verifyToken, version, key, BASE, Map.of(), Map.of(), null);
    }
}
