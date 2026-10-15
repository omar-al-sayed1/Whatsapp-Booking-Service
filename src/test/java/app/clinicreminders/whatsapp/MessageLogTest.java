package app.clinicreminders.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MessageLogTest {

    @Test
    void statusOnlyMovesForward() {
        MessageLog log = MessageLog.sent(1, null, MessageKind.TEXT, null, "wamid.1", "service", null);

        log.applyStatus(MessageStatus.READ, null, null);
        log.applyStatus(MessageStatus.DELIVERED, null, null);
        log.applyStatus(MessageStatus.SENT, null, null);

        assertThat(log.getStatus()).isEqualTo(MessageStatus.READ);
    }

    @Test
    void failureRecordsMetasError() {
        MessageLog log = MessageLog.sent(1, null, MessageKind.TEMPLATE, "appointment_reminder", "wamid.1", "utility", null);

        log.applyStatus(MessageStatus.FAILED, 131026, "Message undeliverable");

        assertThat(log.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(log.getErrorCode()).isEqualTo(131026);
        assertThat(log.getErrorTitle()).isEqualTo("Message undeliverable");
    }

    @Test
    void untrackedWebhookStatusesAreIgnored() {
        assertThat(MessageStatus.fromWebhook("played")).isNull();
        assertThat(MessageStatus.fromWebhook("delivered")).isEqualTo(MessageStatus.DELIVERED);
    }
}
