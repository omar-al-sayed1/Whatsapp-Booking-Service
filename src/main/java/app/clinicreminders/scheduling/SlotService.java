package app.clinicreminders.scheduling;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads one clinic's data for a doctor and asks the {@link SlotEngine} for free slots. */
@Service
public class SlotService {

    private final ClinicRepository clinics;
    private final DoctorRepository doctors;
    private final ScheduleRuleRepository rules;
    private final ScheduleExceptionRepository exceptions;
    private final AppointmentRepository appointments;
    private final Clock clock;

    SlotService(ClinicRepository clinics, DoctorRepository doctors, ScheduleRuleRepository rules,
                ScheduleExceptionRepository exceptions, AppointmentRepository appointments, Clock clock) {
        this.clinics = clinics;
        this.doctors = doctors;
        this.rules = rules;
        this.exceptions = exceptions;
        this.appointments = appointments;
        this.clock = clock;
    }

    /**
     * Free slots for the clinic-local dates {@code from} to {@code to}, inclusive.
     *
     * @throws NotFoundException if the doctor doesn't exist in this clinic
     */
    @Transactional(readOnly = true)
    public List<Slot> freeSlots(long clinicId, long doctorId, LocalDate from, LocalDate to) {
        Clinic clinic = clinics.findById(clinicId).orElseThrow(() -> new NotFoundException("Clinic", clinicId));
        Doctor doctor = doctors.findByIdAndClinicId(doctorId, clinicId)
                .orElseThrow(() -> new NotFoundException("Doctor", doctorId));
        if (!doctor.isActive()) {
            return List.of();
        }
        ZoneId zone = clinic.getTimezone();

        DoctorSchedule.Builder schedule = DoctorSchedule.builder(zone, doctor.slotLength());
        for (ScheduleRule rule : rules.findByClinicIdAndDoctorId(clinicId, doctorId)) {
            schedule.weekly(rule.getDayOfWeek(), rule.window().start(), rule.window().end());
        }
        for (ScheduleException exception : exceptions.findByClinicIdAndDoctorIdAndExceptionDateBetween(
                clinicId, doctorId, from, to)) {
            exception.hours().ifPresentOrElse(
                    hours -> schedule.hoursOn(exception.getExceptionDate(), hours.start(), hours.end()),
                    () -> schedule.closedOn(exception.getExceptionDate()));
        }

        // atStartOfDay(zone) handles a midnight that doesn't exist on a DST change day.
        Instant rangeStart = from.atStartOfDay(zone).toInstant();
        Instant rangeEnd = to.plusDays(1).atStartOfDay(zone).toInstant();
        List<TimeRange> busy = appointments.findActiveOverlapping(clinicId, doctorId, rangeStart, rangeEnd).stream()
                .map(a -> new TimeRange(a.getStartsAt(), a.getEndsAt()))
                .toList();

        return SlotEngine.freeSlots(schedule.build(), from, to, busy, clock.instant());
    }
}
