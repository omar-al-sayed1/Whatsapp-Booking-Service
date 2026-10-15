package app.clinicreminders.whatsapp.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the inbox in the background: straight after a delivery is stored, and on a sweep that picks
 * up anything left behind (a restart, a failed attempt). Off in tests, which drain explicitly.
 */
@Component
@ConditionalOnProperty(name = "whatsapp.inbox.async", havingValue = "true", matchIfMissing = true)
class WebhookInboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(WebhookInboxScheduler.class);

    private final WebhookInboxProcessor processor;
    private final TaskExecutor executor;

    WebhookInboxScheduler(WebhookInboxProcessor processor, @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.processor = processor;
        this.executor = executor;
    }

    @EventListener
    void onDeliveryStored(WebhookInbox.DeliveryStored event) {
        executor.execute(this::drain);
    }

    @Scheduled(fixedDelayString = "${whatsapp.inbox.sweep-interval}")
    void sweep() {
        drain();
    }

    private void drain() {
        try {
            processor.drain();
        } catch (RuntimeException e) {
            log.error("Webhook inbox processing failed", e);
        }
    }
}
