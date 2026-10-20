package app.clinicreminders.whatsapp.webhook;

import org.jspecify.annotations.Nullable;

/**
 * Published once per new inbound message (duplicates and opt-outs are not published), inside the
 * transaction that logged it. Listeners (Confirm/Cancel in M3, booking in M5) run in that
 * transaction: if one throws, the delivery is retried later and nothing is half-applied.
 *
 * @param patientId a patient matched by phone or WhatsApp user id, if any: the first added, as a
 *                  family's patients share one number
 */
public record InboundMessageReceived(long clinicId, @Nullable Long patientId, long messageLogId,
                                     InboundMessage message) {
}
