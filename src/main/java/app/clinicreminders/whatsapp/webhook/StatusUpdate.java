package app.clinicreminders.whatsapp.webhook;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A delivery status for a message we sent, parsed from {@code value.statuses[]}.
 *
 * @param status          Meta's value: sent, delivered, read, failed or played
 * @param pricingCategory {@code pricing.category}, e.g. utility or service
 * @param pricingType     {@code pricing.type}: regular, free_customer_service or free_entry_point
 * @param errorCode       {@code errors[0].code} on failure
 */
public record StatusUpdate(
        String waMessageId,
        String status,
        @Nullable Instant timestamp,
        @Nullable String recipientPhoneE164,
        @Nullable String recipientUserId,
        @Nullable String pricingCategory,
        @Nullable String pricingType,
        @Nullable Integer errorCode,
        @Nullable String errorTitle) {
}
