package app.clinicreminders.whatsapp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;

import app.clinicreminders.whatsapp.OutboundMessage.ReplyButton;
import app.clinicreminders.whatsapp.OutboundMessage.ReplyButtonsMessage;
import app.clinicreminders.whatsapp.OutboundMessage.TextMessage;
import org.junit.jupiter.api.Test;

/** Meta's limits for reply buttons: up to 3 buttons, titles up to 20 characters and unique. */
class OutboundMessageTest {

    private static final String TO = "+96170123456";

    @Test
    void acceptsUpToThreeButtons() {
        assertThatCode(() -> new ReplyButtonsMessage(TO, "Pick one",
                List.of(button("a"), button("b"), button("c")))).doesNotThrowAnyException();
        assertThatIllegalArgumentException().isThrownBy(() -> new ReplyButtonsMessage(TO, "Pick one",
                List.of(button("a"), button("b"), button("c"), button("d"))));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReplyButtonsMessage(TO, "Pick one", List.of()));
    }

    @Test
    void buttonTitlesAreAtMostTwentyCharactersAndUnique() {
        assertThatCode(() -> new ReplyButton("id", "x".repeat(20))).doesNotThrowAnyException();
        assertThatIllegalArgumentException().isThrownBy(() -> new ReplyButton("id", "x".repeat(21)));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReplyButtonsMessage(TO, "Pick one",
                List.of(new ReplyButton("1", "Same"), new ReplyButton("2", "Same"))));
    }

    @Test
    void recipientsMustBeE164() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TextMessage("03 123 456", "hi"));
        assertThatIllegalArgumentException().isThrownBy(() -> new TextMessage("96170123456", "hi"));
    }

    private static ReplyButton button(String title) {
        return new ReplyButton(title, title);
    }
}
