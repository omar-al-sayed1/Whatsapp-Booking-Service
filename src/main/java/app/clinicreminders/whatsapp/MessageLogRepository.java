package app.clinicreminders.whatsapp;

import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

public interface MessageLogRepository extends Repository<MessageLog, Long> {

    MessageLog save(MessageLog log);

    Optional<MessageLog> findByClinicIdAndWaMessageId(Long clinicId, String waMessageId);

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
