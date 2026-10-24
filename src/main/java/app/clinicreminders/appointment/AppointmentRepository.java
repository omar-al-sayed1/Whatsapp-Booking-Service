package app.clinicreminders.appointment;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.clinicreminders.common.CrossTenant;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

public interface AppointmentRepository extends Repository<Appointment, Long> {

    Optional<Appointment> findByIdAndClinicId(Long id, Long clinicId);

    @CrossTenant("Public calendar page: the unguessable token is the key, and the page shows one appointment's "
            + "time, doctor and clinic, never the patient")
    Optional<Appointment> findByCalendarToken(UUID calendarToken);

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

    /** The patient's next active appointment ("My appointment" on WhatsApp). */
    @Query("""
            select a from Appointment a
            where a.clinicId = :clinicId and a.patientId = :patientId and a.startsAt > :after
              and a.status in (app.clinicreminders.appointment.AppointmentStatus.BOOKED,
                               app.clinicreminders.appointment.AppointmentStatus.CONFIRMED)
            order by a.startsAt
            limit 1""")
    Optional<Appointment> findNextOpen(Long clinicId, Long patientId, Instant after);

    /** The open (booked or confirmed) appointments still to come of these patients, earliest first: the Patients page. */
    @Query("""
            select a from Appointment a
            where a.clinicId = :clinicId and a.patientId in :patientIds and a.startsAt > :after
              and a.status in (app.clinicreminders.appointment.AppointmentStatus.BOOKED,
                               app.clinicreminders.appointment.AppointmentStatus.CONFIRMED)
            order by a.startsAt""")
    List<Appointment> findUpcomingOf(Long clinicId, Collection<Long> patientIds, Instant after);

    /** How many open (booked or confirmed) appointments the doctor still has to come: deleting them waits for none. */
    @Query("""
            select count(a) from Appointment a
            where a.clinicId = :clinicId and a.doctorId = :doctorId and a.startsAt > :after
              and a.status in (app.clinicreminders.appointment.AppointmentStatus.BOOKED,
                               app.clinicreminders.appointment.AppointmentStatus.CONFIRMED)""")
    long countUpcomingOfDoctor(Long clinicId, Long doctorId, Instant after);

    /**
     * Books unless the doctor already has an active appointment starting then. Unlike
     * {@link #saveAndFlush}, losing the race returns empty instead of throwing, so the caller's
     * transaction stays usable (the WhatsApp flow runs inside the webhook inbox's transaction).
     */
    @Transactional
    @Query(value = """
            insert into appointment (clinic_id, doctor_id, patient_id, starts_at, ends_at, status, source)
            values (:clinicId, :doctorId, :patientId, :startsAt, :endsAt, 'BOOKED', :source)
            on conflict (doctor_id, starts_at) where status <> 'CANCELLED' do nothing
            returning id""", nativeQuery = true)
    Optional<Long> insertUnlessTaken(Long clinicId, Long doctorId, Long patientId, Instant startsAt, Instant endsAt,
                                     String source);

    Appointment save(Appointment appointment);

    /** Flushes so the double-booking constraint is checked here, not at commit. */
    Appointment saveAndFlush(Appointment appointment);
}
