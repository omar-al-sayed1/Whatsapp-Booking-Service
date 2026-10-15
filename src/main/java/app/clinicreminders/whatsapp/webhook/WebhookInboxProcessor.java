package app.clinicreminders.whatsapp.webhook;

import java.util.List;
import java.util.Optional;

import app.clinicreminders.whatsapp.WhatsAppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processes stored webhook deliveries, one per transaction: claim the oldest unclaimed row
 * ({@code FOR UPDATE SKIP LOCKED}, so several threads or instances never process the same one),
 * handle it, delete it. On failure the transaction rolls back, the attempt is counted, and after
 * {@code whatsapp.inbox.max-attempts} the row is kept as failed for the operator.
 */
@Component
public class WebhookInboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(WebhookInboxProcessor.class);

    private record Delivery(long id, String payload) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final WebhookEventHandler handler;
    private final int maxAttempts;

    WebhookInboxProcessor(JdbcTemplate jdbc, TransactionTemplate transaction, WebhookEventHandler handler,
                          WhatsAppProperties properties) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.handler = handler;
        this.maxAttempts = properties.inbox().maxAttempts();
    }

    /**
     * Processes every waiting delivery once, oldest first. A delivery that fails is retried on the
     * next call, not in this one.
     *
     * @return how many deliveries were attempted
     */
    public int drain() {
        int attempted = 0;
        long after = 0;
        Optional<Long> processed;
        while ((processed = processNext(after)).isPresent()) {
            after = processed.get();
            attempted++;
        }
        return attempted;
    }

    private Optional<Long> processNext(long after) {
        Delivery[] claimed = new Delivery[1];
        try {
            return transaction.execute(status -> {
                List<Delivery> next = jdbc.query("""
                        select id, payload from webhook_inbox
                        where not failed and id > ?
                        order by id
                        limit 1
                        for update skip locked""",
                        (rs, row) -> new Delivery(rs.getLong("id"), rs.getString("payload")), after);
                if (next.isEmpty()) {
                    return Optional.empty();
                }
                claimed[0] = next.getFirst();
                handler.handle(claimed[0].payload());
                jdbc.update("delete from webhook_inbox where id = ?", claimed[0].id());
                return Optional.of(claimed[0].id());
            });
        } catch (RuntimeException e) {
            if (claimed[0] == null) {
                throw e;
            }
            recordFailure(claimed[0].id(), e);
            return Optional.of(claimed[0].id());
        }
    }

    private void recordFailure(long id, RuntimeException e) {
        String error = String.valueOf(e.getMessage());
        error = error.length() > 1000 ? error.substring(0, 1000) : error;
        jdbc.update("""
                update webhook_inbox
                set attempts = attempts + 1, last_error = ?, failed = attempts + 1 >= ?
                where id = ?""", e.getClass().getSimpleName() + ": " + error, maxAttempts, id);
        log.warn("Webhook delivery {} failed: {}", id, e.toString());
    }
}
