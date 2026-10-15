package app.clinicreminders.common;

import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/** E.164 phone numbers, matching the {@code patient.phone_e164} check constraint. */
public final class PhoneNumbers {

    private static final Pattern E164 = Pattern.compile("\\+[1-9][0-9]{6,14}");

    private PhoneNumbers() {
    }

    public static boolean isE164(@Nullable String phone) {
        return phone != null && E164.matcher(phone).matches();
    }

    public static String requireE164(String phone) {
        if (!isE164(phone)) {
            throw new IllegalArgumentException("Not an E.164 phone number: " + phone);
        }
        return phone;
    }

    /**
     * Reads a number as a secretary types it: "+961 3 123 456", "00961 3 123456", "03 123 456" and
     * "70123456" all mean a Lebanese number. Spaces, dashes, dots and brackets are ignored.
     *
     * @return E.164, or null if it can't be a phone number
     */
    public static @Nullable String parseLebanese(@Nullable String input) {
        if (input == null) {
            return null;
        }
        String cleaned = input.replaceAll("[\\s\\-.()/]", "");
        boolean international = cleaned.startsWith("+");
        String digits = international ? cleaned.substring(1) : cleaned;
        if (!international && digits.startsWith("00")) {
            digits = digits.substring(2);
            international = true;
        }
        if (!digits.chars().allMatch(Character::isDigit) || digits.isEmpty()) {
            return null;
        }
        if (!international) {
            if (digits.startsWith("961") && digits.length() >= 10) {
                international = true;
            } else {
                // Lebanese national numbers: 7 or 8 digits, written with a leading 0 for some prefixes.
                String national = digits.startsWith("0") ? digits.substring(1) : digits;
                if (national.length() < 7 || national.length() > 8) {
                    return null;
                }
                digits = "961" + national;
            }
        }
        String phone = "+" + digits;
        return isE164(phone) ? phone : null;
    }

    /** WhatsApp sends numbers as digits without '+'. Returns null if it isn't a valid number. */
    public static @Nullable String fromWhatsApp(@Nullable String digits) {
        if (digits == null) {
            return null;
        }
        String phone = "+" + digits;
        return isE164(phone) ? phone : null;
    }
}
