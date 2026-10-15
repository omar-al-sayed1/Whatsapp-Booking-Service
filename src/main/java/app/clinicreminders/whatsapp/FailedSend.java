package app.clinicreminders.whatsapp;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/** A message Meta refused or we couldn't send, with the appointment and patient it was for, if any. */
public record FailedSend(
        Long messageLogId,
        Instant failedAt,
        MessageKind kind,
        @Nullable String templateName,
        @Nullable Integer errorCode,
        @Nullable String errorTitle,
        @Nullable Long appointmentId,
        @Nullable Instant appointmentStartsAt,
        @Nullable String patientName,
        @Nullable String patientPhone) {
}
