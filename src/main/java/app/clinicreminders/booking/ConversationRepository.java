package app.clinicreminders.booking;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

public interface ConversationRepository extends Repository<Conversation, Long> {

    List<Conversation> findByClinicIdAndNeedsHumanTrueOrderByUpdatedAtDesc(Long clinicId);

    Optional<Conversation> findByIdAndClinicId(Long id, Long clinicId);

    /** Removes the conversation with this number (when the patient is deleted on request). */
    @Transactional
    long deleteByClinicIdAndPatientPhone(Long clinicId, String patientPhone);

    Conversation save(Conversation conversation);
}
