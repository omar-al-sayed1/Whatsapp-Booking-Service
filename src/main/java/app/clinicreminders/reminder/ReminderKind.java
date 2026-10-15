package app.clinicreminders.reminder;

public enum ReminderKind {

    /** 24 hours before; moved earlier if it falls in quiet hours. */
    H24,
    /** 2 hours before; skipped if it falls in quiet hours. */
    H2
}
