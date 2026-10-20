package app.clinicreminders.booking;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

public interface ConversationRepository extends Repository<Conversation, Long> {

    List<Conversation> findByClinicIdAndNeedsHumanTrueOrderByUpdatedAtDesc(Long clinicId);

    long countByClinicIdAndNeedsHumanTrue(Long clinicId);

    Optional<Conversation> findByIdAndClinicId(Long id, Long clinicId);

    /**
     * Locks the conversation for the rest of the transaction, so two quick taps from the same
     * patient (processed by different workers) are handled one after the other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Conversation> findForUpdateByClinicIdAndPatientPhone(Long clinicId, String patientPhone);

    /**
     * Starts a conversation for this number unless one exists. Two first messages from a new number
     * can't both insert, so follow with {@link #findForUpdateByClinicIdAndPatientPhone}.
     */
    @Transactional
    @Modifying
    @Query(value = """
            insert into conversation (clinic_id, patient_phone, state, expires_at)
            values (:clinicId, :patientPhone, :state, :expiresAt)
            on conflict (clinic_id, patient_phone) do nothing""", nativeQuery = true)
    int insertIfAbsent(Long clinicId, String patientPhone, String state, Instant expiresAt);

    /** Removes the conversation with this number (when the patient is deleted on request). */
    @Transactional
    long deleteByClinicIdAndPatientPhone(Long clinicId, String patientPhone);

    Conversation save(Conversation conversation);
}
