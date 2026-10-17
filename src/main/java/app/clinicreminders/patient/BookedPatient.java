package app.clinicreminders.patient;

import java.time.Instant;

import app.clinicreminders.common.Language;

/** A patient who has had at least one appointment, with the start of their latest one (cancelled ones count). */
public record BookedPatient(Long id, String name, String phoneE164, Language language, boolean optedOut,
                            Instant lastAppointment) {
}
