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
        Doctor doctor = doctor(clinicId, doctorId);
        if (!doctor.isActive()) {
            return List.of();
        }
        ZoneId zone = zone(clinicId);
        // atStartOfDay(zone) handles a midnight that doesn't exist on a DST change day.
        Instant rangeStart = from.atStartOfDay(zone).toInstant();
        Instant rangeEnd = to.plusDays(1).atStartOfDay(zone).toInstant();
        List<TimeRange> busy = appointments.findActiveOverlapping(clinicId, doctorId, rangeStart, rangeEnd).stream()
                .map(a -> new TimeRange(a.getStartsAt(), a.getEndsAt()))
                .toList();
        return SlotEngine.freeSlots(schedule(clinicId, doctor, zone, from, to), from, to, busy, clock.instant());
    }

    /** Every slot of the doctor's opening hours, booked or not, past or not (the week grid). */
    @Transactional(readOnly = true)
    public List<Slot> allSlots(long clinicId, long doctorId, LocalDate from, LocalDate to) {
        Doctor doctor = doctor(clinicId, doctorId);
        ZoneId zone = zone(clinicId);
        return SlotEngine.freeSlots(schedule(clinicId, doctor, zone, from, to), from, to, List.of(), Instant.MIN);
    }

    private DoctorSchedule schedule(long clinicId, Doctor doctor, ZoneId zone, LocalDate from, LocalDate to) {
        DoctorSchedule.Builder schedule = DoctorSchedule.builder(zone, doctor.slotLength());
        for (ScheduleRule rule : rules.findByClinicIdAndDoctorId(clinicId, doctor.getId())) {
            schedule.weekly(rule.getDayOfWeek(), rule.window().start(), rule.window().end());
        }
        for (ScheduleException exception : exceptions.findByClinicIdAndDoctorIdAndExceptionDateBetween(
                clinicId, doctor.getId(), from, to)) {
            exception.hours().ifPresentOrElse(
                    hours -> schedule.hoursOn(exception.getExceptionDate(), hours.start(), hours.end()),
                    () -> schedule.closedOn(exception.getExceptionDate()));
        }
        return schedule.build();
    }

    private Doctor doctor(long clinicId, long doctorId) {
        return doctors.findByIdAndClinicId(doctorId, clinicId).orElseThrow(() -> new NotFoundException("Doctor", doctorId));
    }

    private ZoneId zone(long clinicId) {
        return clinics.findById(clinicId).map(Clinic::getTimezone)
                .orElseThrow(() -> new NotFoundException("Clinic", clinicId));
    }
}
