package app.clinicreminders.whatsapp.webhook;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Durable hand-off between the webhook endpoint and processing: a verified delivery is committed
 * here before we answer 200, because Meta stops retrying once it gets a 200.
 */
@Component
public class WebhookInbox {

    /** Published after a delivery is stored, to wake the processor. */
    public record DeliveryStored() {
    }

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    WebhookInbox(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    /** Stores the raw payload; committed when this returns (no surrounding transaction). */
    public void store(String payload) {
        jdbc.update("insert into webhook_inbox (payload) values (?)", payload);
        events.publishEvent(new DeliveryStored());
    }
}
