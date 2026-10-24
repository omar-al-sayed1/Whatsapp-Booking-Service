package app.clinicreminders.report;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentStatus;
import app.clinicreminders.appointment.AppointmentView;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.report.MonthlyReport.DoctorFigures;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.scheduling.Slot;
import app.clinicreminders.scheduling.SlotService;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The monthly report (SPEC section 5.4), for the clinic's dashboard, a doctor's own login and the
 * operator. Counts are taken when asked, so a month still running shows what happened so far.
 */
@Service
public class Reports {

    private final ReportQueries queries;
    private final ClinicRepository clinics;
    private final DoctorRepository doctors;
    private final AppointmentRepository appointments;
    private final SlotService slots;

    Reports(ReportQueries queries, ClinicRepository clinics, DoctorRepository doctors, AppointmentRepository appointments,
            SlotService slots) {
        this.queries = queries;
        this.clinics = clinics;
        this.doctors = doctors;
        this.appointments = appointments;
        this.slots = slots;
    }

    /**
     * One clinic's month, in its own zone.
     *
     * @param doctorId only this doctor's appointments, and no messages (they are the clinic's); null for the clinic
     */
    @Transactional(readOnly = true)
    public MonthlyReport monthly(long clinicId, YearMonth month, @Nullable Long doctorId, Instant now) {
        Clinic clinic = clinics.findById(clinicId).orElseThrow(() -> new NotFoundException("Clinic", clinicId));
        ReportMonth period = new ReportMonth(month, clinic.getTimezone());
        Map<Long, AppointmentFigures> byDoctor = queries.appointmentsByDoctor(clinicId, period, now);
        Map<Long, Long> reminders = queries.remindersSentByDoctor(clinicId, period);
        List<DoctorFigures> rows = doctors.findByClinicIdOrderByDisplayName(clinicId).stream()
                .filter(doctor -> doctorId == null || doctor.getId().equals(doctorId))
                .filter(doctor -> listed(doctor, byDoctor))
                .map(doctor -> new DoctorFigures(doctor.getId(), doctor.getDisplayName(),
                        byDoctor.getOrDefault(doctor.getId(), AppointmentFigures.NONE)
                                .withRemindersSent(reminders.getOrDefault(doctor.getId(), 0L))))
                .toList();
        AppointmentFigures total = rows.stream().map(DoctorFigures::figures)
                .reduce(AppointmentFigures.NONE, AppointmentFigures::plus);
        return new MonthlyReport(clinicId, clinic.getName(), period, total, rows,
                doctorId == null ? queries.messages(clinicId, period) : List.of());
    }

    /** Every clinic's month, by name: the operator's overview. */
    @Transactional(readOnly = true)
    public List<MonthlyReport> allClinics(YearMonth month, Instant now) {
        return clinics.findAllByOrderByNameAscIdAsc().stream()
                .map(clinic -> monthly(clinic.getId(), month, null, now))
                .toList();
    }

    /**
     * How full each hour of the week was in the month, for the report's doctors (one doctor's
     * report: theirs only).
     */
    @Transactional(readOnly = true)
    public BusyHours busyHours(MonthlyReport report) {
        long clinicId = report.clinicId();
        ZoneId zone = report.month().zone();
        YearMonth month = report.month().month();
        Set<Long> doctorIds = report.doctors().stream().map(DoctorFigures::doctorId).collect(Collectors.toSet());
        Map<String, Long> booked = new HashMap<>();
        for (AppointmentView appointment : appointments.findViews(clinicId, report.month().from(), report.month().to())) {
            if (appointment.status() != AppointmentStatus.CANCELLED && doctorIds.contains(appointment.doctorId())) {
                booked.merge(key(appointment.startsAt().atZone(zone)), 1L, Long::sum);
            }
        }
        Map<String, Long> open = new HashMap<>();
        LocalDate first = month.atDay(1);
        for (long doctorId : doctorIds) {
            for (Slot slot : slots.allSlots(clinicId, doctorId, first, month.atEndOfMonth())) {
                open.merge(key(slot.start().atZone(zone)), 1L, Long::sum);
            }
        }
        TreeSet<Integer> hours = new TreeSet<>();
        TreeSet<DayOfWeek> days = new TreeSet<>();
        for (String key : union(booked, open)) {
            days.add(DayOfWeek.of(Integer.parseInt(key.substring(0, 1))));
            hours.add(Integer.parseInt(key.substring(2)));
        }
        if (!hours.isEmpty()) {
            // A gap (a lunch break) stays visible as closed hours.
            for (int hour = hours.first(); hour <= hours.last(); hour++) {
                hours.add(hour);
            }
        }
        List<BusyHours.Day> rows = new ArrayList<>();
        for (DayOfWeek day : days) {
            rows.add(new BusyHours.Day(day, hours.stream()
                    .map(hour -> new BusyHours.Cell(day, hour, booked.getOrDefault(day.getValue() + ":" + hour, 0L),
                            open.getOrDefault(day.getValue() + ":" + hour, 0L)))
                    .toList()));
        }
        return new BusyHours(List.copyOf(hours), rows);
    }

    /** "2:10": Tuesday, 10:00 to 10:59. */
    private static String key(ZonedDateTime time) {
        return time.getDayOfWeek().getValue() + ":" + time.getHour();
    }

    private static Set<String> union(Map<String, Long> a, Map<String, Long> b) {
        Set<String> keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        return keys;
    }

    /** Whether the doctor's figures go in a report: they had appointments, or take them. */
    private static boolean listed(Doctor doctor, Map<Long, AppointmentFigures> byDoctor) {
        return byDoctor.containsKey(doctor.getId()) || (doctor.isActive() && !doctor.isDeleted());
    }
}
