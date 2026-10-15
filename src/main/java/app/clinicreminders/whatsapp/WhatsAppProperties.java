package app.clinicreminders.whatsapp;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WhatsApp settings, bound from {@code whatsapp.*} (values come from environment variables).
 * Startup fails with the variable's name if something required is missing.
 *
 * @param templateCategories Meta pricing category of each approved template, by template name
 * @param markets            Meta rate-card market by country calling code (e.g. 961), used for cost estimates
 */
@ConfigurationProperties("whatsapp")
public record WhatsAppProperties(
        ClientType client,
        String appSecret,
        String verifyToken,
        String graphVersion,
        String tokenEncryptionKey,
        URI baseUrl,
        Map<String, String> templateCategories,
        Map<String, String> markets,
        Inbox inbox) {

    public enum ClientType {
        /** Meta's Cloud API. The default, so production can never silently use the fake. */
        CLOUD,
        /** Logs messages and backs the dev simulator page. */
        FAKE
    }

    /**
     * @param async         process stored webhooks in the background (off in tests, which drain explicitly)
     * @param sweepInterval how often to pick up deliveries that weren't processed straight away
     * @param maxAttempts   attempts before a delivery is marked failed and left for the operator
     */
    public record Inbox(boolean async, Duration sweepInterval, int maxAttempts) {
    }

    public WhatsAppProperties {
        client = client == null ? ClientType.CLOUD : client;
        templateCategories = templateCategories == null ? Map.of() : Map.copyOf(templateCategories);
        markets = markets == null ? Map.of() : Map.copyOf(markets);
        inbox = inbox == null ? new Inbox(true, Duration.ofSeconds(10), 5) : inbox;
        require(appSecret, "WA_APP_SECRET");
        require(verifyToken, "WA_VERIFY_TOKEN");
        if (client == ClientType.CLOUD) {
            require(tokenEncryptionKey, "TOKEN_ENCRYPTION_KEY");
            if (graphVersion == null || !graphVersion.matches("v\\d+\\.\\d+")) {
                throw new IllegalStateException("WA_GRAPH_VERSION must look like v26.0 but was: " + graphVersion);
            }
        }
    }

    private static void require(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variable + " must be set");
        }
    }

    @Override
    public String toString() {
        return "WhatsAppProperties[client=" + client + ", graphVersion=" + graphVersion + ", secrets redacted]";
    }
}
