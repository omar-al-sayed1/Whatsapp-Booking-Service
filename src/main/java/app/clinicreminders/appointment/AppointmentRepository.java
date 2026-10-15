package app.clinicreminders.appointment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface AppointmentRepository extends Repository<Appointment, Long> {

    Optional<Appointment> findByIdAndClinicId(Long id, Long clinicId);

    /** Active (not cancelled) appointments of one doctor that overlap {@code [from, to)}. */
    @Query("""
            select a from Appointment a
            where a.clinicId = :clinicId
              and a.doctorId = :doctorId
              and a.status <> app.clinicreminders.appointment.AppointmentStatus.CANCELLED
              and a.startsAt < :to
              and a.endsAt > :from
            order by a.startsAt
            """)
    List<Appointment> findActiveOverlapping(Long clinicId, Long doctorId, Instant from, Instant to);

    /** Every appointment (cancelled ones too) starting in {@code [from, to)}, with its patient. */
    @Query("""
            select new app.clinicreminders.appointment.AppointmentView(
                a.id, a.doctorId, a.startsAt, a.endsAt, a.status, a.source, a.cancelledBy,
                a.patientId, p.name, p.phoneE164)
            from Appointment a
            left join Patient p on p.id = a.patientId and p.clinicId = a.clinicId
            where a.clinicId = :clinicId and a.startsAt >= :from and a.startsAt < :to
            order by a.startsAt, a.id
            """)
    List<AppointmentView> findViews(Long clinicId, Instant from, Instant to);

    List<Appointment> findByClinicIdAndPatientIdAndStartsAtAfter(Long clinicId, Long patientId, Instant after);

    Appointment save(Appointment appointment);

    /** Flushes so the double-booking constraint is checked here, not at commit. */
    Appointment saveAndFlush(Appointment appointment);
}
