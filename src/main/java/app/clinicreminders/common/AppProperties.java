package app.clinicreminders.common;

import java.net.URI;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code app.*}.
 *
 * @param baseUrl the public address of this service ({@code APP_BASE_URL}), e.g. https://reminders.example.com;
 *                links sent to patients start with it
 */
@ConfigurationProperties("app")
public record AppProperties(URI baseUrl) {

    public AppProperties {
        Objects.requireNonNull(baseUrl, "app.base-url (APP_BASE_URL) is required");
    }

    /** {@code path} ("/c/...") on the public address, without a doubled slash. */
    public String link(String path) {
        String base = baseUrl.toString();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path;
    }
}
