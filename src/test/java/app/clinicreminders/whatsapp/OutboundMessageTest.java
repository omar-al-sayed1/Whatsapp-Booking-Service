package app.clinicreminders.whatsapp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.stream.IntStream;

import app.clinicreminders.whatsapp.OutboundMessage.ListMessage;
import app.clinicreminders.whatsapp.OutboundMessage.ListRow;
import app.clinicreminders.whatsapp.OutboundMessage.ReplyButton;
import app.clinicreminders.whatsapp.OutboundMessage.ReplyButtonsMessage;
import app.clinicreminders.whatsapp.OutboundMessage.TextMessage;
import org.junit.jupiter.api.Test;

/**
 * Meta's limits. Reply buttons: up to 3, titles up to 20 characters and unique. Lists: 1 to 10 rows,
 * row ids up to 200 characters, titles 24, descriptions 72; button 20, section title 24, body 4096.
 */
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
    void listsHaveOneToTenRows() {
        assertThatCode(() -> list(rows(10))).doesNotThrowAnyException();
        assertThatIllegalArgumentException().isThrownBy(() -> list(rows(11)));
        assertThatIllegalArgumentException().isThrownBy(() -> list(List.of()));
    }

    @Test
    void listRowLimits() {
        assertThatCode(() -> new ListRow("x".repeat(200), "x".repeat(24), "x".repeat(72))).doesNotThrowAnyException();
        assertThatCode(() -> new ListRow("id", "title", null)).doesNotThrowAnyException();
        assertThatIllegalArgumentException().isThrownBy(() -> new ListRow("x".repeat(201), "title", null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ListRow("id", "x".repeat(25), null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ListRow("id", "", null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ListRow("id", "title", "x".repeat(73)));
    }

    @Test
    void listButtonSectionTitleAndBodyLimits() {
        assertThatCode(() -> new ListMessage(TO, "x".repeat(4096), "x".repeat(20), "x".repeat(24), rows(1)))
                .doesNotThrowAnyException();
        assertThatIllegalArgumentException().isThrownBy(() -> new ListMessage(TO, "x".repeat(4097), "Days", "Days", rows(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> new ListMessage(TO, "Pick", "x".repeat(21), "Days", rows(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> new ListMessage(TO, "Pick", "Days", "x".repeat(25), rows(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> new ListMessage(TO, "Pick", "Days", "", rows(1)));
    }

    @Test
    void recipientsMustBeE164() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TextMessage("03 123 456", "hi"));
        assertThatIllegalArgumentException().isThrownBy(() -> new TextMessage("96170123456", "hi"));
    }

    private static ReplyButton button(String title) {
        return new ReplyButton(title, title);
    }

    private static ListMessage list(List<ListRow> rows) {
        return new ListMessage(TO, "Pick a day", "Days", "Next free days", rows);
    }

    private static List<ListRow> rows(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> new ListRow("day:" + i, "Day " + i, null)).toList();
    }
}
