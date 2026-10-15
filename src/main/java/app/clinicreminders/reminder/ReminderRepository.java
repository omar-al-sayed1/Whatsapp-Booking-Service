package app.clinicreminders.reminder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import app.clinicreminders.common.CrossTenant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

public interface ReminderRepository extends Repository<Reminder, Long> {

    Reminder save(Reminder reminder);

    List<Reminder> findByClinicIdAndAppointmentIdOrderByDueAt(Long clinicId, Long appointmentId);

    /** Cancels the appointment's reminders that haven't been claimed yet. */
    @Modifying
    @Transactional
    @Query("""
            update Reminder r set r.status = app.clinicreminders.reminder.ReminderStatus.CANCELLED
            where r.clinicId = :clinicId and r.appointmentId = :appointmentId
              and r.status = app.clinicreminders.reminder.ReminderStatus.PENDING""")
    int cancelPending(Long clinicId, Long appointmentId);

    /**
     * Claims the oldest due reminder and marks it SENDING in one statement, skipping rows another
     * worker holds. Committed before anything is sent: at most once, even across a crash.
     *
     * @return the claimed reminder's id, or empty when nothing is due
     */
    @CrossTenant("The scheduler sends every clinic's reminders")
    @Transactional
    @Query(value = """
            update reminder set status = 'SENDING', attempts = attempts + 1
            where id = (select id from reminder
                        where status = 'PENDING' and due_at <= :now
                        order by due_at, id
                        limit 1
                        for update skip locked)
            returning id""", nativeQuery = true)
    Optional<Long> claimNextDue(Instant now);

    @CrossTenant("The scheduler loads the reminder it has just claimed; the id comes from claimNextDue")
    @Query("select r from Reminder r where r.id = :id and r.status = app.clinicreminders.reminder.ReminderStatus.SENDING")
    Optional<Reminder> findClaimed(Long id);
}
