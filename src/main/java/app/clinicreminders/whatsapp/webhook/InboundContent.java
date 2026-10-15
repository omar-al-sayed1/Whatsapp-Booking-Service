package app.clinicreminders.whatsapp.webhook;

import app.clinicreminders.whatsapp.MessageKind;
import org.jspecify.annotations.Nullable;

/** What the patient sent. Field names follow Meta's messages webhook reference. */
public sealed interface InboundContent {

    MessageKind kind();

    /** {@code type: "text"}. */
    record Text(String body) implements InboundContent {
        public MessageKind kind() {
            return MessageKind.TEXT;
        }
    }

    /**
     * {@code type: "button"}: a tap on a template's quick-reply button. Meta's reference shows both
     * fields carrying the button label, so callers match on the label (and {@code context.id}).
     */
    record QuickReply(String payload, String text) implements InboundContent {
        public MessageKind kind() {
            return MessageKind.BUTTON_REPLY;
        }
    }

    /** {@code interactive.type: "button_reply"}: a tap on one of our reply buttons. */
    record ButtonReply(String id, String title) implements InboundContent {
        public MessageKind kind() {
            return MessageKind.BUTTON_REPLY;
        }
    }

    /** {@code interactive.type: "list_reply"}: a row picked from one of our list messages. */
    record ListReply(String id, String title, @Nullable String description) implements InboundContent {
        public MessageKind kind() {
            return MessageKind.INTERACTIVE;
        }
    }

    /** Images, voice notes, locations, stickers and anything else. */
    record Unsupported(String type) implements InboundContent {
        public MessageKind kind() {
            return MessageKind.OTHER;
        }
    }
}
