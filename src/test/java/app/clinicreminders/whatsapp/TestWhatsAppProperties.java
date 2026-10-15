package app.clinicreminders.whatsapp;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import app.clinicreminders.whatsapp.WhatsAppProperties.ClientType;
import app.clinicreminders.whatsapp.WhatsAppProperties.Inbox;

/** Properties for unit tests; the values match the ones integration tests set. */
public final class TestWhatsAppProperties {

    public static final String APP_SECRET = "test-app-secret";
    public static final String VERIFY_TOKEN = "test-verify-token";
    /** 32 bytes (0..31), base64. A test value, not a secret. */
    public static final String ENCRYPTION_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private TestWhatsAppProperties() {
    }

    public static WhatsAppProperties fake() {
        return fakeWithAppSecret(APP_SECRET);
    }

    public static WhatsAppProperties fakeWithAppSecret(String appSecret) {
        return new WhatsAppProperties(ClientType.FAKE, appSecret, VERIFY_TOKEN, "v26.0", ENCRYPTION_KEY,
                URI.create("https://graph.facebook.com"),
                Map.of("appointment_reminder", "utility", "appointment_reminder_soon", "utility"),
                Map.of("961", "Rest of Middle East", "1", "North America"),
                new Inbox(false, Duration.ofSeconds(10), 5));
    }
}
