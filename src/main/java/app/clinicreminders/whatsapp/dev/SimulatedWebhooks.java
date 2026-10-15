package app.clinicreminders.whatsapp.dev;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds webhook payloads shaped like Meta's (messages webhook reference, October 2026) for the
 * dev simulator and integration tests. Parser tests use hand-written fixtures instead, so a
 * mistake here can't hide a mistake in the parser.
 */
public final class SimulatedWebhooks {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SimulatedWebhooks() {
    }

    public static String newMessageId() {
        return "wamid.DEV." + UUID.randomUUID();
    }

    public static String text(String phoneNumberId, String fromE164, @Nullable String profileName, String id,
                              String body, Instant at) {
        ObjectNode message = message(fromE164, id, "text", at);
        message.putObject("text").put("body", body);
        return envelope(phoneNumberId, fromE164, profileName, message);
    }

    /** A tap on a template's quick-reply button: {@code type: "button"} with {@code context.id}. */
    public static String quickReply(String phoneNumberId, String fromE164, @Nullable String profileName, String id,
                                    String repliedToId, String label, Instant at) {
        ObjectNode message = message(fromE164, id, "button", at);
        message.putObject("context").put("from", "dev").put("id", repliedToId);
        message.putObject("button").put("payload", label).put("text", label);
        return envelope(phoneNumberId, fromE164, profileName, message);
    }

    /** A tap on one of our interactive reply buttons. */
    public static String buttonReply(String phoneNumberId, String fromE164, @Nullable String profileName, String id,
                                     String repliedToId, String buttonId, String title, Instant at) {
        ObjectNode message = message(fromE164, id, "interactive", at);
        message.putObject("context").put("from", "dev").put("id", repliedToId);
        ObjectNode interactive = message.putObject("interactive").put("type", "button_reply");
        interactive.putObject("button_reply").put("id", buttonId).put("title", title);
        return envelope(phoneNumberId, fromE164, profileName, message);
    }

    /** A row picked from one of our list messages. */
    public static String listReply(String phoneNumberId, String fromE164, @Nullable String profileName, String id,
                                   String repliedToId, String rowId, String title, Instant at) {
        ObjectNode message = message(fromE164, id, "interactive", at);
        message.putObject("context").put("from", "dev").put("id", repliedToId);
        ObjectNode interactive = message.putObject("interactive").put("type", "list_reply");
        interactive.putObject("list_reply").put("id", rowId).put("title", title);
        return envelope(phoneNumberId, fromE164, profileName, message);
    }

    /**
     * A status for a message we sent.
     *
     * @param status sent, delivered, read or failed
     */
    public static String status(String phoneNumberId, String waMessageId, String recipientE164, String status,
                                @Nullable String pricingCategory, @Nullable String pricingType,
                                @Nullable Integer errorCode, @Nullable String errorTitle, Instant at) {
        ObjectNode root = root();
        ObjectNode value = value(root, phoneNumberId);
        ObjectNode statusNode = value.putArray("statuses").addObject()
                .put("id", waMessageId)
                .put("status", status)
                .put("timestamp", String.valueOf(at.getEpochSecond()))
                .put("recipient_id", recipientE164.substring(1));
        if (pricingCategory != null) {
            statusNode.putObject("pricing")
                    .put("billable", true)
                    .put("pricing_model", "PMP")
                    .put("type", pricingType == null ? "regular" : pricingType)
                    .put("category", pricingCategory);
        }
        if (errorCode != null) {
            statusNode.putArray("errors").addObject()
                    .put("code", errorCode)
                    .put("title", errorTitle)
                    .put("message", errorTitle);
        }
        return JSON.writeValueAsString(root);
    }

    private static ObjectNode message(String fromE164, String id, String type, Instant at) {
        return JSON.createObjectNode()
                .put("from", fromE164.substring(1))
                .put("id", id)
                .put("timestamp", String.valueOf(at.getEpochSecond()))
                .put("type", type);
    }

    private static String envelope(String phoneNumberId, String fromE164, @Nullable String profileName,
                                   ObjectNode message) {
        ObjectNode root = root();
        ObjectNode value = value(root, phoneNumberId);
        ObjectNode contact = value.putArray("contacts").addObject();
        contact.putObject("profile").put("name", profileName == null ? "Dev Patient" : profileName);
        contact.put("wa_id", fromE164.substring(1));
        value.putArray("messages").add(message);
        return JSON.writeValueAsString(root);
    }

    private static ObjectNode root() {
        return JSON.createObjectNode().put("object", "whatsapp_business_account");
    }

    private static ObjectNode value(ObjectNode root, String phoneNumberId) {
        ObjectNode change = root.putArray("entry").addObject().put("id", "dev-waba")
                .putArray("changes").addObject().put("field", "messages");
        ObjectNode value = change.putObject("value").put("messaging_product", "whatsapp");
        value.putObject("metadata").put("display_phone_number", "dev").put("phone_number_id", phoneNumberId);
        return value;
    }
}
