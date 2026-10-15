package app.clinicreminders.whatsapp.webhook;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A message a patient sent us, parsed from a webhook.
 *
 * @param phoneE164        the sender's number; Meta omits it for users with a username who haven't
 *                         been in contact with this business number in the last 30 days
 * @param userId           Meta's business-scoped user id (BSUID); present even when the number isn't
 * @param profileName      the sender's WhatsApp profile name, if Meta sent one
 * @param contextMessageId for replies and button taps: the id of the message replied to
 */
public record InboundMessage(
        String waMessageId,
        @Nullable String phoneE164,
        @Nullable String userId,
        @Nullable String profileName,
        @Nullable String contextMessageId,
        @Nullable Instant timestamp,
        InboundContent content) {
}
