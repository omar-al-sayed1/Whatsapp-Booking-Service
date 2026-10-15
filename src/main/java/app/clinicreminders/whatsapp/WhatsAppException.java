package app.clinicreminders.whatsapp;

import java.util.Set;

import org.jspecify.annotations.Nullable;

/** A failed send. {@link #isRetryable()} decides whether {@link WhatsAppMessenger} tries again. */
public class WhatsAppException extends RuntimeException {

    /**
     * Meta error codes worth retrying: temporary downtime (2), API/account rate limits (4, 80007),
     * throughput (130429), unknown error (131000), service unavailable (131016) and too many messages
     * to one recipient (131056). Source: Cloud API error codes reference.
     */
    static final Set<Integer> RETRYABLE_CODES = Set.of(2, 4, 80007, 130429, 131000, 131016, 131056);

    private final @Nullable Integer code;
    private final String title;
    private final boolean retryable;

    public WhatsAppException(@Nullable Integer code, String title, boolean retryable) {
        super(code == null ? title : "(#" + code + ") " + title);
        this.code = code;
        this.title = title;
        this.retryable = retryable;
    }

    public static WhatsAppException fromMetaError(int code, String title) {
        return new WhatsAppException(code, title, RETRYABLE_CODES.contains(code));
    }

    /** Meta's error code, or null for network errors and errors raised before calling Meta. */
    public @Nullable Integer getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
