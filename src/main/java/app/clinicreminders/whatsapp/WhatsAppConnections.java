package app.clinicreminders.whatsapp;

import java.util.Optional;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.NotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Onboarding (SPEC section 5.5): stores a clinic's WhatsApp phone number id, business account id
 * and access token (encrypted, never shown again), and subscribes the account to our webhooks.
 */
@Service
public class WhatsAppConnections {

    static final int ID_MAX = 64;
    static final int TOKEN_MAX = 2048;

    /** What the operator page needs to know about a clinic's connection; never the token itself. */
    public record Connection(@Nullable String phoneNumberId, @Nullable String businessAccountId, boolean hasToken) {
    }

    /** Why a connection can't be saved or used; the operator page shows each differently. */
    public enum Problem { PHONE_NUMBER_ID_TAKEN, TOO_LONG, NO_ENCRYPTION_KEY, NOT_CONNECTED }

    public static class ConnectionException extends RuntimeException {

        private final Problem problem;

        ConnectionException(Problem problem) {
            super(problem.name());
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    private final ClinicRepository clinics;
    private final TokenCipher tokenCipher;
    private final WhatsAppClient client;

    WhatsAppConnections(ClinicRepository clinics, TokenCipher tokenCipher, WhatsAppClient client) {
        this.clinics = clinics;
        this.tokenCipher = tokenCipher;
        this.client = client;
    }

    @Transactional(readOnly = true)
    public Connection connection(long clinicId) {
        Clinic clinic = clinic(clinicId);
        return new Connection(clinic.getWaPhoneNumberId(), clinic.getWaBusinessAccountId(),
                clinic.getWaAccessTokenEncrypted() != null);
    }

    /**
     * Blank ids are cleared (the clinic stops receiving webhooks). A blank token keeps the stored
     * one, so the operator never has to paste it again to fix an id.
     *
     * @throws ConnectionException if the phone number id belongs to another clinic, a value is too
     *                             long, or a token is given without TOKEN_ENCRYPTION_KEY; nothing is saved
     */
    @Transactional
    public void connect(long clinicId, @Nullable String phoneNumberId, @Nullable String businessAccountId,
                        @Nullable String newToken, boolean removeToken) {
        Clinic clinic = clinic(clinicId);
        String phone = blankToNull(phoneNumberId, ID_MAX);
        String account = blankToNull(businessAccountId, ID_MAX);
        String token = blankToNull(newToken, TOKEN_MAX);
        if (phone != null) {
            Optional<Clinic> holder = clinics.findByWaPhoneNumberId(phone);
            if (holder.isPresent() && !holder.get().getId().equals(clinicId)) {
                throw new ConnectionException(Problem.PHONE_NUMBER_ID_TAKEN);
            }
        }
        if (token != null && !tokenCipher.isConfigured()) {
            throw new ConnectionException(Problem.NO_ENCRYPTION_KEY);
        }
        clinic.changeWhatsAppIds(phone, account);
        if (removeToken) {
            clinic.changeAccessToken(null);
        } else if (token != null) {
            clinic.changeAccessToken(tokenCipher.encrypt(clinicId, token));
        }
    }

    /**
     * Subscribes our Meta app to the clinic's WhatsApp Business Account. Calls Meta, so it runs
     * without a transaction.
     *
     * @throws ConnectionException if the clinic has no business account id or token
     * @throws WhatsAppException   if Meta refused or couldn't be reached
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void subscribeToWebhooks(long clinicId) {
        Clinic clinic = clinic(clinicId);
        if (clinic.getWaBusinessAccountId() == null || clinic.getWaAccessTokenEncrypted() == null) {
            throw new ConnectionException(Problem.NOT_CONNECTED);
        }
        if (!tokenCipher.isConfigured()) {
            throw new ConnectionException(Problem.NO_ENCRYPTION_KEY);
        }
        String token = tokenCipher.decrypt(clinicId, clinic.getWaAccessTokenEncrypted());
        client.subscribeApp(clinic.getWaBusinessAccountId(), token);
    }

    private Clinic clinic(long clinicId) {
        return clinics.findById(clinicId).orElseThrow(() -> new NotFoundException("Clinic", clinicId));
    }

    private static @Nullable String blankToNull(@Nullable String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() > max) {
            throw new ConnectionException(Problem.TOO_LONG);
        }
        return stripped;
    }
}
