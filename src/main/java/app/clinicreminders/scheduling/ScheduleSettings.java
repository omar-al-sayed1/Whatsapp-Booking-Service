package app.clinicreminders.scheduling;

import java.time.DayOfWeek;
import java.time.LocalDate;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.NotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Doctors, weekly hours and closed days, as edited on the settings pages, and the clinic's
 * appointment length, set by the operator. Always within one clinic.
 */
@Service
@Transactional
public class ScheduleSettings {

    private final ClinicRepository clinics;
    private final DoctorRepository doctors;
    private final ScheduleRuleRepository rules;
    private final ScheduleExceptionRepository exceptions;

    ScheduleSettings(ClinicRepository clinics, DoctorRepository doctors, ScheduleRuleRepository rules,
                     ScheduleExceptionRepository exceptions) {
        this.clinics = clinics;
        this.doctors = doctors;
        this.rules = rules;
        this.exceptions = exceptions;
    }

    /** A new doctor takes the clinic's appointment length. */
    public Doctor addDoctor(long clinicId, String displayName) {
        return doctors.save(new Doctor(clinicId, requireName(displayName), clinic(clinicId).getSlotMinutes()));
    }

    /**
     * The operator's appointment length for the clinic, applied to all its doctors. Existing
     * appointments keep their times; new slots use the new length.
     *
     * @throws IllegalArgumentException outside 5 to 240 minutes; nothing is changed
     */
    public void changeSlotLength(long clinicId, int minutes) {
        clinic(clinicId).changeSlotMinutes(minutes);
        doctors.findByClinicIdOrderByDisplayName(clinicId).forEach(doctor -> doctor.changeSlotMinutes(minutes));
    }

    public void updateDoctor(long clinicId, long doctorId, String displayName, boolean active) {
        Doctor doctor = doctor(clinicId, doctorId);
        doctor.rename(requireName(displayName));
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

    private Clinic clinic(long clinicId) {
        return clinics.findById(clinicId).orElseThrow(() -> new NotFoundException("Clinic", clinicId));
    }
}
