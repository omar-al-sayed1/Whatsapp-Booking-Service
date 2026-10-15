package app.clinicreminders.whatsapp.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import app.clinicreminders.whatsapp.TestWhatsAppProperties;
import org.junit.jupiter.api.Test;

/** Expected signatures were computed with {@code openssl dgst -sha256 -hmac test-app-secret}. */
class WebhookSignatureVerifierTest {

    private static final byte[] BODY = bytes("{\"object\":\"whatsapp_business_account\",\"entry\":[]}");
    private static final String BODY_SIGNATURE = "sha256=c84a394cc475525991e78ff16e430b02e47f953877d2af0ade26956ce6f384dc";

    private static final byte[] ARABIC_BODY = bytes("{\"text\":{\"body\":\"توقف\"}}");
    private static final String ARABIC_SIGNATURE = "sha256=db9dcc111c60987661ed073390d40cc70f4d11e8ed018b550e2fb1af3771061c";

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(TestWhatsAppProperties.fake());

    @Test
    void acceptsTheSignatureMetaWouldSend() {
        assertThat(verifier.isValid(BODY, BODY_SIGNATURE)).isTrue();
        assertThat(verifier.sign(BODY)).isEqualTo(BODY_SIGNATURE);
    }

    @Test
    void acceptsUppercaseHex() {
        assertThat(verifier.isValid(BODY, "sha256=" + BODY_SIGNATURE.substring(7).toUpperCase())).isTrue();
    }

    @Test
    void hashesTheRawUtf8Bytes() {
        assertThat(verifier.isValid(ARABIC_BODY, ARABIC_SIGNATURE)).isTrue();
        // The same JSON re-serialized with \\u escapes is different bytes, so it must not verify.
        byte[] escaped = bytes("{\"text\":{\"body\":\"\\u062a\\u0648\\u0642\\u0641\"}}");
        assertThat(verifier.isValid(escaped, ARABIC_SIGNATURE)).isFalse();
    }

    @Test
    void rejectsATamperedBody() {
        byte[] tampered = bytes("{\"object\":\"whatsapp_business_account\",\"entry\":[{}]}");
        assertThat(verifier.isValid(tampered, BODY_SIGNATURE)).isFalse();
    }

    @Test
    void rejectsAnotherSecret() {
        var other = new WebhookSignatureVerifier(TestWhatsAppProperties.fakeWithAppSecret("another-secret"));
        assertThat(other.isValid(BODY, BODY_SIGNATURE)).isFalse();
    }

    @Test
    void rejectsMissingOrMalformedHeaders() {
        assertThat(verifier.isValid(BODY, null)).isFalse();
        assertThat(verifier.isValid(BODY, "")).isFalse();
        assertThat(verifier.isValid(BODY, BODY_SIGNATURE.substring(7))).isFalse();
        assertThat(verifier.isValid(BODY, "sha1=" + BODY_SIGNATURE.substring(7))).isFalse();
        assertThat(verifier.isValid(BODY, "sha256=not-hex")).isFalse();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
