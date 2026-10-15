package app.clinicreminders.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class TokenCipherTest {

    private final TokenCipher cipher = new TokenCipher(TestWhatsAppProperties.ENCRYPTION_KEY);

    @Test
    void roundTrips() {
        byte[] stored = cipher.encrypt(7, "EAAG-access-token");

        assertThat(cipher.decrypt(7, stored)).isEqualTo("EAAG-access-token");
    }

    @Test
    void neverStoresThePlainToken() {
        byte[] stored = cipher.encrypt(7, "EAAG-access-token");

        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("EAAG");
        assertThat(stored[0]).isEqualTo((byte) 1);
    }

    @Test
    void usesAFreshNonceEachTime() {
        assertThat(cipher.encrypt(7, "same")).isNotEqualTo(cipher.encrypt(7, "same"));
    }

    @Test
    void anotherClinicCannotDecryptIt() {
        byte[] stored = cipher.encrypt(7, "EAAG-access-token");

        assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(8, stored))
                .withMessageContaining("clinic 8");
    }

    @Test
    void anotherKeyCannotDecryptIt() {
        byte[] stored = cipher.encrypt(7, "EAAG-access-token");
        var otherKey = new TokenCipher(Base64.getEncoder().encodeToString(new byte[32]));

        assertThatIllegalStateException().isThrownBy(() -> otherKey.decrypt(7, stored));
    }

    @Test
    void detectsTampering() {
        byte[] stored = cipher.encrypt(7, "EAAG-access-token");
        stored[stored.length - 1] ^= 1;

        assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(7, stored));
    }

    @Test
    void rejectsAKeyThatIsNot32Bytes() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TokenCipher(Base64.getEncoder().encodeToString(new byte[16])));
    }

    @Test
    void withoutAKeyItFailsOnlyWhenUsed() {
        var noKey = new TokenCipher(null);

        assertThatIllegalStateException().isThrownBy(() -> noKey.encrypt(7, "token"))
                .withMessage("TOKEN_ENCRYPTION_KEY is not set");
    }
}
