package app.clinicreminders.whatsapp;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

public interface MessageLogRepository extends Repository<MessageLog, Long> {

    MessageLog save(MessageLog log);

    Optional<MessageLog> findByClinicIdAndWaMessageId(Long clinicId, String waMessageId);

    /** Outbound messages that failed since {@code since}, newest first, for Needs attention. */
    @Query("""
            select new app.clinicreminders.whatsapp.FailedSend(
                m.id, m.createdAt, m.kind, m.templateName, m.errorCode, m.errorTitle,
                a.id, a.startsAt, p.name, p.phoneE164)
            from MessageLog m
            left join Appointment a on a.id = m.appointmentId and a.clinicId = m.clinicId
            left join Patient p on p.id = a.patientId and p.clinicId = m.clinicId
            where m.clinicId = :clinicId
              and m.direction = app.clinicreminders.whatsapp.MessageDirection.OUT
              and m.status = app.clinicreminders.whatsapp.MessageStatus.FAILED
              and m.createdAt >= :since
            order by m.createdAt desc, m.id desc""")
    List<FailedSend> findFailedSince(Long clinicId, Instant since, Pageable page);

    /** How many {@link #findFailedSince} finds in all: the Needs attention count. */
    @Query("""
            select count(m) from MessageLog m
            where m.clinicId = :clinicId
              and m.direction = app.clinicreminders.whatsapp.MessageDirection.OUT
              and m.status = app.clinicreminders.whatsapp.MessageStatus.FAILED
              and m.createdAt >= :since""")
    long countFailedSince(Long clinicId, Instant since);

    /**
     * Records an inbound message unless its WhatsApp id was seen before; this is how duplicate
     * webhook deliveries are dropped.
     *
     * @return the new row's id, or empty for a duplicate
     */
    @Transactional
    @Query(value = """
            insert into message_log (clinic_id, direction, wa_message_id, kind)
            values (:clinicId, 'IN', :waMessageId, :kind)
            on conflict (wa_message_id) do nothing
            returning id""", nativeQuery = true)
    Optional<Long> insertInboundIfNew(Long clinicId, String waMessageId, String kind);
}
