package app.clinicreminders.booking;

import app.clinicreminders.common.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class Conversations {

    private final ConversationRepository conversations;

    Conversations(ConversationRepository conversations) {
        this.conversations = conversations;
    }

    /** The secretary dealt with it; it leaves Needs attention. */
    public void markHandled(long clinicId, long conversationId) {
        conversations.findByIdAndClinicId(conversationId, clinicId)
                .orElseThrow(() -> new NotFoundException("Conversation", conversationId))
                .markHandled();
    }
}
