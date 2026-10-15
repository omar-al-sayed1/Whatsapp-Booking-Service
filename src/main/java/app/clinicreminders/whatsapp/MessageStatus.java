package app.clinicreminders.whatsapp;

import org.jspecify.annotations.Nullable;

/**
 * Delivery status of an outbound message; inbound messages have none. Declared in the order a
 * message moves through, so a late webhook can never move a status backwards.
 */
public enum MessageStatus {

    SENT,
    DELIVERED,
    READ,
    FAILED;

    /** Maps a status webhook's {@code status}; null for values we don't track (e.g. "played"). */
    public static @Nullable MessageStatus fromWebhook(String status) {
        return switch (status) {
            case "sent" -> SENT;
            case "delivered" -> DELIVERED;
            case "read" -> READ;
            case "failed" -> FAILED;
            default -> null;
        };
    }

    public boolean isAfter(MessageStatus other) {
        return ordinal() > other.ordinal();
    }
}
