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

    /** WhatsApp sends numbers as digits without '+'. Returns null if it isn't a valid number. */
    public static @Nullable String fromWhatsApp(@Nullable String digits) {
        if (digits == null) {
            return null;
        }
        String phone = "+" + digits;
        return isE164(phone) ? phone : null;
    }
}
