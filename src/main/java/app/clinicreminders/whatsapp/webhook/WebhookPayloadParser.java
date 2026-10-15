package app.clinicreminders.whatsapp.webhook;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import app.clinicreminders.common.PhoneNumbers;
import app.clinicreminders.whatsapp.webhook.InboundContent.ButtonReply;
import app.clinicreminders.whatsapp.webhook.InboundContent.ListReply;
import app.clinicreminders.whatsapp.webhook.InboundContent.QuickReply;
import app.clinicreminders.whatsapp.webhook.InboundContent.Text;
import app.clinicreminders.whatsapp.webhook.InboundContent.Unsupported;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses messages webhooks. Paths follow Meta's webhook reference pages for text, button,
 * interactive and status payloads (October 2026). Unknown fields are ignored, missing optional
 * fields become null, and a missing {@code from} is expected (users with usernames).
 */
@Component
public class WebhookPayloadParser {

    private static final Logger log = LoggerFactory.getLogger(WebhookPayloadParser.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** @throws tools.jackson.core.JacksonException if the payload isn't JSON */
    public List<WebhookChange> parse(String payload) {
        JsonNode root = JSON.readTree(payload);
        if (!"whatsapp_business_account".equals(text(root.path("object")))) {
            log.warn("Ignoring webhook for object {}", text(root.path("object")));
            return List.of();
        }
        List<WebhookChange> changes = new ArrayList<>();
        for (JsonNode entry : root.path("entry").values()) {
            for (JsonNode change : entry.path("changes").values()) {
                if (!"messages".equals(text(change.path("field")))) {
                    log.debug("Ignoring webhook field {}", text(change.path("field")));
                    continue;
                }
                JsonNode value = change.path("value");
                String phoneNumberId = text(value.path("metadata").path("phone_number_id"));
                if (phoneNumberId == null) {
                    log.warn("Ignoring a messages change without metadata.phone_number_id");
                    continue;
                }
                List<InboundMessage> messages = new ArrayList<>();
                for (JsonNode message : value.path("messages").values()) {
                    InboundMessage parsed = message(message, value.path("contacts"));
                    if (parsed != null) {
                        messages.add(parsed);
                    }
                }
                List<StatusUpdate> statuses = new ArrayList<>();
                for (JsonNode status : value.path("statuses").values()) {
                    StatusUpdate parsed = status(status);
                    if (parsed != null) {
                        statuses.add(parsed);
                    }
                }
                changes.add(new WebhookChange(phoneNumberId, messages, statuses));
            }
        }
        return changes;
    }

    private @Nullable InboundMessage message(JsonNode message, JsonNode contacts) {
        String id = text(message.path("id"));
        String type = text(message.path("type"));
        if (id == null || type == null) {
            log.warn("Ignoring an inbound message without id or type");
            return null;
        }
        String from = text(message.path("from"));
        String userId = text(message.path("from_user_id"));
        return new InboundMessage(
                id,
                PhoneNumbers.fromWhatsApp(from),
                userId,
                profileName(contacts, from, userId),
                text(message.path("context").path("id")),
                timestamp(message.path("timestamp")),
                content(type, message));
    }

    private static InboundContent content(String type, JsonNode message) {
        switch (type) {
            case "text" -> {
                String body = text(message.path("text").path("body"));
                return body == null ? new Unsupported(type) : new Text(body);
            }
            case "button" -> {
                String payload = text(message.path("button").path("payload"));
                String label = text(message.path("button").path("text"));
                if (payload != null || label != null) {
                    return new QuickReply(payload != null ? payload : label, label != null ? label : payload);
                }
            }
            case "interactive" -> {
                JsonNode interactive = message.path("interactive");
                String interactiveType = text(interactive.path("type"));
                if ("button_reply".equals(interactiveType)) {
                    JsonNode reply = interactive.path("button_reply");
                    String id = text(reply.path("id"));
                    if (id != null) {
                        return new ButtonReply(id, nullToEmpty(text(reply.path("title"))));
                    }
                } else if ("list_reply".equals(interactiveType)) {
                    JsonNode reply = interactive.path("list_reply");
                    String id = text(reply.path("id"));
                    if (id != null) {
                        return new ListReply(id, nullToEmpty(text(reply.path("title"))), text(reply.path("description")));
                    }
                }
                return new Unsupported("interactive:" + interactiveType);
            }
            default -> {
            }
        }
        return new Unsupported(type);
    }

    private static @Nullable StatusUpdate status(JsonNode status) {
        String id = text(status.path("id"));
        String value = text(status.path("status"));
        if (id == null || value == null) {
            log.warn("Ignoring a status without id or status");
            return null;
        }
        JsonNode pricing = status.path("pricing");
        JsonNode error = status.path("errors").path(0);
        return new StatusUpdate(
                id,
                value,
                timestamp(status.path("timestamp")),
                PhoneNumbers.fromWhatsApp(text(status.path("recipient_id"))),
                text(status.path("recipient_user_id")),
                text(pricing.path("category")),
                text(pricing.path("type")),
                error.path("code").isInt() ? error.path("code").intValue() : null,
                text(error.path("title")));
    }

    /** The matching contact's profile name; with a single contact, that one. */
    private static @Nullable String profileName(JsonNode contacts, @Nullable String from, @Nullable String userId) {
        List<JsonNode> all = List.copyOf(contacts.values());
        for (JsonNode contact : all) {
            String waId = text(contact.path("wa_id"));
            String contactUserId = text(contact.path("user_id"));
            if ((from != null && from.equals(waId)) || (userId != null && userId.equals(contactUserId))) {
                return text(contact.path("profile").path("name"));
            }
        }
        return all.size() == 1 ? text(all.getFirst().path("profile").path("name")) : null;
    }

    private static @Nullable Instant timestamp(JsonNode node) {
        String seconds = text(node);
        if (seconds == null) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(seconds));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable String text(JsonNode node) {
        return node.isString() ? node.stringValue() : null;
    }

    private static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
