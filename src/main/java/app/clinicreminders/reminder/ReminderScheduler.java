package app.clinicreminders.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls for due reminders. Off in tests, which call {@link ReminderSender#sendDue} directly. */
@Component
@ConditionalOnProperty(name = "reminders.scheduler.enabled", havingValue = "true", matchIfMissing = true)
class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ReminderSender sender;
    private final int batchSize;

    ReminderScheduler(ReminderSender sender, ReminderProperties properties) {
        this.sender = sender;
        this.batchSize = properties.scheduler().batchSize();
    }

    @Scheduled(fixedDelayString = "${reminders.scheduler.poll-interval}")
    void sendDue() {
        try {
            sender.sendDue(batchSize);
        } catch (RuntimeException e) {
            log.error("Reminder scheduler run failed", e);
        }
    }
}
