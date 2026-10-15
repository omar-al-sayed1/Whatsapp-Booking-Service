package app.clinicreminders.whatsapp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.Nullable;

/**
 * Encrypts per-clinic WhatsApp access tokens at rest with AES-256-GCM.
 *
 * <p>Stored format: {@code version (1 byte) || nonce (12 bytes) || ciphertext + tag}. The clinic id
 * is bound in as associated data, so one clinic's ciphertext can't be copied into another clinic's
 * row and decrypted there. The key is {@code TOKEN_ENCRYPTION_KEY}: 32 random bytes, base64-encoded.
 */
public class TokenCipher {

    private static final byte VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final @Nullable SecretKey key;
    private final SecureRandom random = new SecureRandom();

    /** A null or blank key is allowed (fake client); using the cipher then fails. */
    public TokenCipher(@Nullable String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            return;
        }
        byte[] bytes = Base64.getDecoder().decode(base64Key.trim());
        if (bytes.length != 32) {
            throw new IllegalArgumentException("TOKEN_ENCRYPTION_KEY must be 32 bytes, base64-encoded");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public byte[] encrypt(long clinicId, String token) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(clinicId));
            byte[] ciphertext = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + NONCE_BYTES + ciphertext.length)
                    .put(VERSION).put(nonce).put(ciphertext).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt the access token", e);
        }
    }

    public String decrypt(long clinicId, byte[] stored) {
        if (stored.length <= 1 + NONCE_BYTES || stored[0] != VERSION) {
            throw new IllegalStateException("Unknown access token format for clinic " + clinicId);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, stored, 1, NONCE_BYTES));
            cipher.updateAAD(associatedData(clinicId));
            byte[] plain = cipher.doFinal(stored, 1 + NONCE_BYTES, stored.length - 1 - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Could not decrypt the access token of clinic " + clinicId + " (wrong key or wrong clinic)", e);
        }
    }

    private SecretKey requireKey() {
        if (key == null) {
            throw new IllegalStateException("TOKEN_ENCRYPTION_KEY is not set");
        }
        return key;
    }

    private static byte[] associatedData(long clinicId) {
        return ("clinic:" + clinicId).getBytes(StandardCharsets.UTF_8);
    }
}
