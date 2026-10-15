package app.clinicreminders.whatsapp.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import app.clinicreminders.whatsapp.WhatsAppProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Checks {@code X-Hub-Signature-256: sha256=<hex>}, an HMAC-SHA256 of the request body keyed with
 * the app secret (Meta's "Create a webhook endpoint" page). The HMAC is computed over the raw bytes
 * exactly as received; re-serialized JSON would not match.
 */
@Component
public class WebhookSignatureVerifier {

    private static final String PREFIX = "sha256=";

    private final SecretKeySpec key;

    public WebhookSignatureVerifier(WhatsAppProperties properties) {
        this.key = new SecretKeySpec(properties.appSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public boolean isValid(byte[] body, @Nullable String header) {
        if (header == null || !header.startsWith(PREFIX)) {
            return false;
        }
        byte[] expected = HexFormat.of().formatHex(hmac(body)).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = header.substring(PREFIX.length()).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    /** The header value Meta would send for this body (tests and the dev simulator). */
    public String sign(byte[] body) {
        return PREFIX + HexFormat.of().formatHex(hmac(body));
    }

    private byte[] hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
