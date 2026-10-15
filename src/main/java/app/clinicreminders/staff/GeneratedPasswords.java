package app.clinicreminders.staff;

import java.security.SecureRandom;

/**
 * Passwords the operator hands over once, e.g. on the phone: four groups of four characters
 * without look-alikes (no 0/O, 1/l/I), about 93 bits of randomness.
 */
final class GeneratedPasswords {

    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private GeneratedPasswords() {
    }

    static String generate() {
        StringBuilder password = new StringBuilder(19);
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) {
                password.append('-');
            }
            password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }
}
