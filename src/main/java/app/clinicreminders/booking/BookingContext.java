package app.clinicreminders.booking;

import java.time.Instant;
import java.time.LocalDate;

import app.clinicreminders.common.Language;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the booking flow remembers between messages, stored in {@code conversation.context_json}.
 *
 * @param language      the language this conversation is in (the patient's, or guessed for a new number)
 * @param page          which page of a day's times is showing (more than 10 times are paged)
 * @param menuResent    the menu was already re-sent for unrecognised text; don't send it again
 * @param appointmentId the appointment shown under "My appointment"
 */
record BookingContext(
        @Nullable Language language,
        @Nullable Long doctorId,
        @Nullable LocalDate day,
        @Nullable Instant slot,
        int page,
        boolean menuResent,
        @Nullable Long appointmentId) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static BookingContext start(Language language) {
        return new BookingContext(language, null, null, null, 0, false, null);
    }

    BookingContext withDoctor(long id) {
        return new BookingContext(language, id, null, null, 0, menuResent, null);
    }

    BookingContext withDay(LocalDate value, int newPage) {
        return new BookingContext(language, doctorId, value, null, newPage, menuResent, null);
    }

    BookingContext withSlot(Instant value) {
        return new BookingContext(language, doctorId, day, value, page, menuResent, null);
    }

    BookingContext withAppointment(long id) {
        return new BookingContext(language, doctorId, day, slot, page, menuResent, id);
    }

    BookingContext menuResentOnce() {
        return new BookingContext(language, null, null, null, 0, true, null);
    }

    BookingContext cleared() {
        return new BookingContext(language, null, null, null, 0, menuResent, null);
    }

    String toJson() {
        ObjectNode node = JSON.createObjectNode();
        if (language != null) {
            node.put("language", language.code());
        }
        if (doctorId != null) {
            node.put("doctorId", doctorId);
        }
        if (day != null) {
            node.put("day", day.toString());
        }
        if (slot != null) {
            node.put("slot", slot.toString());
        }
        if (page != 0) {
            node.put("page", page);
        }
        if (menuResent) {
            node.put("menuResent", true);
        }
        if (appointmentId != null) {
            node.put("appointmentId", appointmentId);
        }
        return JSON.writeValueAsString(node);
    }

    static BookingContext fromJson(@Nullable String json) {
        JsonNode node = JSON.readTree(json == null || json.isBlank() ? "{}" : json);
        return new BookingContext(
                node.path("language").isString() ? Language.fromCode(node.path("language").stringValue()) : null,
                node.path("doctorId").isNumber() ? node.path("doctorId").longValue() : null,
                node.path("day").isString() ? LocalDate.parse(node.path("day").stringValue()) : null,
                node.path("slot").isString() ? Instant.parse(node.path("slot").stringValue()) : null,
                node.path("page").isNumber() ? node.path("page").intValue() : 0,
                node.path("menuResent").isBoolean() && node.path("menuResent").booleanValue(),
                node.path("appointmentId").isNumber() ? node.path("appointmentId").longValue() : null);
    }
}
