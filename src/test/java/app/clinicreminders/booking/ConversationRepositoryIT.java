package app.clinicreminders.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.clinic.Clinic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class ConversationRepositoryIT extends IntegrationTest {

    private static final String PHONE = "+96170123456";

    @Autowired
    ConversationRepository conversations;

    @Autowired
    TransactionTemplate transactions;

    private Clinic clinic;
    private Clinic other;

    @BeforeEach
    void twoClinics() {
        clinic = data.clinic("A");
        other = data.clinic("B");
    }

    @Test
    void insertIfAbsentStartsOneConversationPerClinicAndNumber() {
        Instant now = clock.instant();

        assertThat(conversations.insertIfAbsent(clinic.getId(), PHONE, "MENU", now)).isEqualTo(1);
        assertThat(conversations.insertIfAbsent(clinic.getId(), PHONE, "CHOOSE_DAY", now.plusSeconds(60))).isZero();
        assertThat(conversations.insertIfAbsent(other.getId(), PHONE, "MENU", now)).isEqualTo(1);

        Conversation started = locked(clinic, PHONE);
        assertThat(started.getState()).isEqualTo(ConversationState.MENU);
        assertThat(started.getContextJson()).isEqualTo("{}");
        assertThat(started.isExpired(now)).as("new conversations start at the menu").isTrue();
        assertThat(started.isNeedsHuman()).isFalse();
    }

    @Test
    void findForUpdateFindsOnlyItsClinicsConversation() {
        conversations.insertIfAbsent(clinic.getId(), PHONE, "MENU", clock.instant());

        Boolean foundInOtherClinic = transactions.execute(status ->
                conversations.findForUpdateByClinicIdAndPatientPhone(other.getId(), PHONE).isPresent());

        assertThat(foundInOtherClinic).isFalse();
        assertThat(locked(clinic, PHONE).getClinicId()).isEqualTo(clinic.getId());
    }

    @Test
    void movingKeepsTheContextAndExtendsTheSession() {
        conversations.insertIfAbsent(clinic.getId(), PHONE, "MENU", clock.instant());
        Instant later = clock.instant().plusSeconds(1800);

        transactions.executeWithoutResult(status -> locked(clinic, PHONE)
                .moveTo(ConversationState.CHOOSE_DAY, "{\"language\":\"ar\",\"doctorId\":7}", later));

        Conversation moved = locked(clinic, PHONE);
        assertThat(moved.getState()).isEqualTo(ConversationState.CHOOSE_DAY);
        assertThat(BookingContext.fromJson(moved.getContextJson()).doctorId()).isEqualTo(7L);
        assertThat(moved.isExpired(later.minusSeconds(1))).isFalse();
        assertThat(moved.isExpired(later)).isTrue();
    }

    private Conversation locked(Clinic owner, String phone) {
        return transactions.execute(status ->
                conversations.findForUpdateByClinicIdAndPatientPhone(owner.getId(), phone).orElseThrow());
    }
}
