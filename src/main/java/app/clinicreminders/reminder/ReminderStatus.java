package app.clinicreminders.reminder;

public enum ReminderStatus {

    PENDING,
    /** Claimed by a worker. Never claimed again, so a crash mid-send can't send twice. */
    SENDING,
    SENT,
    /** Not sent on purpose: quiet hours, too late, opted out, patient deleted, reminders switched off. */
    SKIPPED,
    /** The appointment was cancelled or moved. */
    CANCELLED,
    /** Meta refused it or couldn't be reached after retries; shown under Needs attention. */
    FAILED
}
