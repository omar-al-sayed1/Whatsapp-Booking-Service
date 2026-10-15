package app.clinicreminders.dashboard;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicSettings;
import app.clinicreminders.common.Language;
import app.clinicreminders.staff.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/** The logged-in user's clinic, its zone and "today", and formats in the user's language. */
@Component
class ClinicContext {

    record Ctx(long clinicId, Clinic clinic, ZoneId zone, Instant now, LocalDate today, Language language,
               Formats formats) {

        Instant instant(LocalDate date, LocalTime time) {
            return LocalDateTime.of(date, time).atZone(zone).toInstant();
        }

        Instant startOf(LocalDate date) {
            return date.atStartOfDay(zone).toInstant();
        }

        LocalDate dateOf(Instant instant) {
            return instant.atZone(zone).toLocalDate();
        }

        LocalTime timeOf(Instant instant) {
            return instant.atZone(zone).toLocalTime();
        }
    }

    private final ClinicSettings clinics;
    private final MessageSource messages;
    private final Clock clock;

    ClinicContext(ClinicSettings clinics, MessageSource messages, Clock clock) {
        this.clinics = clinics;
        this.messages = messages;
        this.clock = clock;
    }

    Ctx of(StaffPrincipal staff, HttpServletRequest request) {
        long clinicId = staff.requireClinicId();
        Clinic clinic = clinics.get(clinicId);
        ZoneId zone = clinic.getTimezone();
        Instant now = clock.instant();
        Language language = StaffLocaleResolver.language(request);
        return new Ctx(clinicId, clinic, zone, now, now.atZone(zone).toLocalDate(), language,
                new Formats(zone, language, messages));
    }
}
