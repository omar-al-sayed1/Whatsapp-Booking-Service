package app.clinicreminders.whatsapp.webhook;

import java.util.List;

/** One {@code entry[].changes[]} of a messages webhook: everything for one business phone number. */
public record WebhookChange(String phoneNumberId, List<InboundMessage> messages, List<StatusUpdate> statuses) {

    public WebhookChange {
        messages = List.copyOf(messages);
        statuses = List.copyOf(statuses);
    }
}
