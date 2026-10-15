package app.clinicreminders.whatsapp;

import org.jspecify.annotations.Nullable;

/**
 * A clinic's WhatsApp sender: its phone number id and decrypted access token.
 * Never logged with the token: see {@link #toString()}.
 */
public record WhatsAppAccount(String phoneNumberId, @Nullable String accessToken) {

    @Override
    public String toString() {
        return "WhatsAppAccount[phoneNumberId=" + phoneNumberId + ", accessToken=<redacted>]";
    }
}
