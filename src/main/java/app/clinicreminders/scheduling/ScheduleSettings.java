package app.clinicreminders.scheduling;

import java.time.DayOfWeek;
import java.time.LocalDate;

import app.clinicreminders.common.NotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Doctors, weekly hours and closed days, as edited on the settings pages. Always within one clinic. */
@Service
@Transactional
public class ScheduleSettings {

    private final DoctorRepository doctors;
    private final ScheduleRuleRepository rules;
    private final ScheduleExceptionRepository exceptions;

    ScheduleSettings(DoctorRepository doctors, ScheduleRuleRepository rules, ScheduleExceptionRepository exceptions) {
        this.doctors = doctors;
        this.rules = rules;
        this.exceptions = exceptions;
    }

    public Doctor addDoctor(long clinicId, String displayName, int slotMinutes) {
        return doctors.save(new Doctor(clinicId, requireName(displayName), requireSlotMinutes(slotMinutes)));
    }

    /** Existing appointments keep their times; the new slot length applies to new bookings. */
    public void updateDoctor(long clinicId, long doctorId, String displayName, int slotMinutes, boolean active) {
        Doctor doctor = doctor(clinicId, doctorId);
        doctor.rename(requireName(displayName));
        doctor.changeSlotMinutes(requireSlotMinutes(slotMinutes));
        if (active) {
            doctor.activate();
        } else {
            doctor.deactivate();
        }
    }

    public void addWeeklyHours(long clinicId, long doctorId, DayOfWeek day, TimeWindow window) {
        doctor(clinicId, doctorId);
        rules.save(new ScheduleRule(clinicId, doctorId, day, window));
    }

    public void removeWeeklyHours(long clinicId, long ruleId) {
        rules.delete(rules.findByIdAndClinicId(ruleId, clinicId).orElseThrow(() -> new NotFoundException("Hours", ruleId)));
    }

    /**
     * Closes the doctor on {@code date}, or sets special hours that day (null {@code hours} means
     * closed). Replaces any earlier exception for that date.
     */
    public void setDay(long clinicId, long doctorId, LocalDate date, @Nullable TimeWindow hours) {
        doctor(clinicId, doctorId);
        exceptions.findByClinicIdAndDoctorIdAndExceptionDate(clinicId, doctorId, date).ifPresentOrElse(
                existing -> existing.change(hours),
                () -> exceptions.save(hours == null
                        ? ScheduleException.closed(clinicId, doctorId, date)
                        : ScheduleException.withHours(clinicId, doctorId, date, hours)));
    }

    public void removeDay(long clinicId, long exceptionId) {
        exceptions.delete(exceptions.findByIdAndClinicId(exceptionId, clinicId)
                .orElseThrow(() -> new NotFoundException("Day", exceptionId)));
    }

    private Doctor doctor(long clinicId, long doctorId) {
        return doctors.findByIdAndClinicId(doctorId, clinicId).orElseThrow(() -> new NotFoundException("Doctor", doctorId));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A doctor needs a name");
        }
        return name.strip();
    }

    private static int requireSlotMinutes(int minutes) {
        if (minutes < 5 || minutes > 240) {
            throw new IllegalArgumentException("Slot length must be 5-240 minutes");
        }
        return minutes;
    }
}
