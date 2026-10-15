package app.clinicreminders.booking;

import app.clinicreminders.whatsapp.OutboundMessage;
import app.clinicreminders.whatsapp.WhatsAppMessenger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the booking flow's reply once the step that decided it has committed, outside any
 * transaction so the HTTP call holds no connection. If the step rolls back (and the delivery is
 * retried), nothing was sent.
 */
@Component
class BookingReplySender {

    /** @param appointmentId the appointment the reply is about, for {@code message_log} */
    record BookingReply(long clinicId, OutboundMessage message, @Nullable Long appointmentId) {
    }

    private static final Logger log = LoggerFactory.getLogger(BookingReplySender.class);

    private final WhatsAppMessenger messenger;

    BookingReplySender(WhatsAppMessenger messenger) {
        this.messenger = messenger;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void onReply(BookingReply reply) {
        try {
            messenger.send(reply.clinicId(), reply.message(), reply.appointmentId());
        } catch (RuntimeException e) {
            log.error("Clinic {}: could not send a booking reply", reply.clinicId(), e);
        }
    }
}
