package app.clinicreminders.whatsapp.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import app.clinicreminders.whatsapp.webhook.InboundContent.ButtonReply;
import app.clinicreminders.whatsapp.webhook.InboundContent.ListReply;
import app.clinicreminders.whatsapp.webhook.InboundContent.QuickReply;
import app.clinicreminders.whatsapp.webhook.InboundContent.Text;
import app.clinicreminders.whatsapp.webhook.InboundContent.Unsupported;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;

/** Fixtures in src/test/resources/whatsapp follow Meta's messages webhook reference. */
class WebhookPayloadParserTest {

    private final WebhookPayloadParser parser = new WebhookPayloadParser();

    @Test
    void textMessage() {
        WebhookChange change = single("text.json");

        assertThat(change.phoneNumberId()).isEqualTo("106540352242922");
        assertThat(change.statuses()).isEmpty();
        InboundMessage message = change.messages().getFirst();
        assertThat(message.waMessageId()).isEqualTo("wamid.HBgLMTY1MDM4Nzk0MzkVAgASGBQzQTRBNjU5OUFFRTAzODEwMTQ0RgA=");
        assertThat(message.phoneE164()).isEqualTo("+96170123456");
        assertThat(message.userId()).isEqualTo("LB.13491208655302741918");
        assertThat(message.profileName()).isEqualTo("Rana Haddad");
        assertThat(message.contextMessageId()).isNull();
        assertThat(message.timestamp()).isEqualTo(Instant.ofEpochSecond(1792064620));
        assertThat(message.content()).isEqualTo(new Text("مرحبا، بدي موعد"));
    }

    @Test
    void templateQuickReplyCarriesTheTappedMessageId() {
        InboundMessage message = single("quick-reply.json").messages().getFirst();

        assertThat(message.content()).isEqualTo(new QuickReply("تأكيد", "تأكيد"));
        assertThat(message.contextMessageId()).isEqualTo("wamid.TEMPLATE-REMINDER-1");
    }

    @Test
    void interactiveRepliesAndUnsupportedTypes() {
        List<InboundMessage> messages = single("interactive-replies.json").messages();

        assertThat(messages).extracting(InboundMessage::content).containsExactly(
                new ButtonReply("doctor-12", "Dr. Khoury"),
                new ListReply("slot-2026-11-02T07:00Z", "Mon 09:00", "Dr. Khoury"),
                new Unsupported("image"));
        assertThat(messages).extracting(InboundMessage::contextMessageId)
                .containsExactly("wamid.BUTTONS-1", "wamid.LIST-1", null);
    }

    @Test
    void aMessageWithoutThePhoneNumberStillHasTheUserId() {
        InboundMessage message = single("hidden-number.json").messages().getFirst();

        assertThat(message.phoneE164()).isNull();
        assertThat(message.userId()).isEqualTo("LB.99887766554433221100");
        assertThat(message.profileName()).isEqualTo("Karim");
        assertThat(message.content()).isEqualTo(new Text("STOP"));
    }

    @Test
    void statusesWithPricingAndErrors() {
        List<StatusUpdate> statuses = single("statuses.json").statuses();

        assertThat(statuses).hasSize(3);
        StatusUpdate delivered = statuses.get(0);
        assertThat(delivered.status()).isEqualTo("delivered");
        assertThat(delivered.recipientPhoneE164()).isEqualTo("+96170123456");
        assertThat(delivered.recipientUserId()).isEqualTo("LB.13491208655302741918");
        assertThat(delivered.pricingCategory()).isEqualTo("utility");
        assertThat(delivered.pricingType()).isEqualTo("regular");
        assertThat(delivered.errorCode()).isNull();

        StatusUpdate failed = statuses.get(1);
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.errorCode()).isEqualTo(131026);
        assertThat(failed.errorTitle()).isEqualTo("Message undeliverable");
        assertThat(failed.pricingCategory()).isNull();

        assertThat(statuses.get(2).status()).isEqualTo("played");
    }

    @Test
    void oneDeliveryCanCarrySeveralPhoneNumbersAndOtherFields() {
        List<WebhookChange> changes = parser.parse(read("several-numbers.json"));

        assertThat(changes).extracting(WebhookChange::phoneNumberId).containsExactly("PHONE-A", "PHONE-B");
        assertThat(changes).flatExtracting(WebhookChange::messages).extracting(InboundMessage::content)
                .containsExactly(new Text("hi A"), new Text("hi B"));
    }

    @Test
    void ignoresOtherObjects() {
        assertThat(parser.parse("{\"object\":\"page\",\"entry\":[]}")).isEmpty();
    }

    @Test
    void ignoresInvalidPhoneNumbers() {
        String payload = read("text.json").replace("\"from\": \"96170123456\"", "\"from\": \"12\"");

        assertThat(parser.parse(payload).getFirst().messages().getFirst().phoneE164()).isNull();
    }

    @Test
    void rejectsSomethingThatIsNotJson() {
        assertThatThrownBy(() -> parser.parse("not json")).isInstanceOf(JacksonException.class);
    }

    private WebhookChange single(String fixture) {
        List<WebhookChange> changes = parser.parse(read(fixture));
        assertThat(changes).hasSize(1);
        return changes.getFirst();
    }

    static String read(String fixture) {
        try (InputStream in = WebhookPayloadParserTest.class.getResourceAsStream("/whatsapp/" + fixture)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
