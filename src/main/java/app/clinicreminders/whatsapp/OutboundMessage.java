package app.clinicreminders.whatsapp;

import java.util.HashSet;
import java.util.List;

import app.clinicreminders.common.PhoneNumbers;
import org.jspecify.annotations.Nullable;

/**
 * A message we send. Limits are Meta's, from the interactive reply buttons and interactive list
 * messages references (developers.facebook.com/documentation/business-messaging/whatsapp/messages/).
 */
public sealed interface OutboundMessage {

    /** Recipient in E.164. */
    String to();

    /**
     * An approved template, e.g. a reminder. Body parameters fill {{1}}, {{2}}... in order.
     *
     * @param languageCode the language code the template was approved under (e.g. "ar", "en")
     */
    record TemplateMessage(String to, String name, String languageCode, List<String> bodyParameters)
            implements OutboundMessage {

        public TemplateMessage {
            PhoneNumbers.requireE164(to);
            bodyParameters = List.copyOf(bodyParameters);
        }
    }

    /** Free text. Only allowed inside the 24-hour customer service window. */
    record TextMessage(String to, String body) implements OutboundMessage {

        public TextMessage {
            PhoneNumbers.requireE164(to);
        }
    }

    /** Up to three reply buttons. Only allowed inside the 24-hour customer service window. */
    record ReplyButtonsMessage(String to, String body, List<ReplyButton> buttons) implements OutboundMessage {

        public ReplyButtonsMessage {
            PhoneNumbers.requireE164(to);
            if (body.isEmpty() || body.length() > 1024) {
                throw new IllegalArgumentException("Body must be 1-1024 characters");
            }
            if (buttons.isEmpty() || buttons.size() > 3) {
                throw new IllegalArgumentException("Between 1 and 3 buttons are allowed");
            }
            if (new HashSet<>(buttons.stream().map(ReplyButton::title).toList()).size() != buttons.size()) {
                throw new IllegalArgumentException("Button titles must be unique");
            }
            buttons = List.copyOf(buttons);
        }
    }

    /**
     * A list the patient opens with {@code button} and picks one row from. One section, as every
     * list we send has one; Meta requires its title even then. Only allowed inside the 24-hour
     * customer service window.
     */
    record ListMessage(String to, String body, String button, String sectionTitle, List<ListRow> rows)
            implements OutboundMessage {

        public ListMessage {
            PhoneNumbers.requireE164(to);
            requireLength("Body", body, 4096);
            requireLength("Button", button, 20);
            requireLength("Section title", sectionTitle, 24);
            if (rows.isEmpty() || rows.size() > 10) {
                throw new IllegalArgumentException("Between 1 and 10 rows are allowed");
            }
            rows = List.copyOf(rows);
        }
    }

    record ListRow(String id, String title, @Nullable String description) {

        public ListRow {
            requireLength("Row id", id, 200);
            requireLength("Row title", title, 24);
            if (description != null && description.length() > 72) {
                throw new IllegalArgumentException("Row description must be at most 72 characters: " + description);
            }
        }
    }

    private static void requireLength(String what, String value, int max) {
        if (value == null || value.isEmpty() || value.length() > max) {
            throw new IllegalArgumentException(what + " must be 1-" + max + " characters: " + value);
        }
    }

    record ReplyButton(String id, String title) {

        public ReplyButton {
            if (id.isEmpty() || id.length() > 256) {
                throw new IllegalArgumentException("Button id must be 1-256 characters");
            }
            if (title.isEmpty() || title.length() > 20) {
                throw new IllegalArgumentException("Button title must be 1-20 characters: " + title);
            }
        }
    }
}
