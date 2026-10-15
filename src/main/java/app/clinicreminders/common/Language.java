package app.clinicreminders.common;

import java.util.Arrays;

/** Languages for patients, staff and clinics. Stored as the ISO code ({@code ar}, {@code en}). */
public enum Language {

    AR("ar"),
    EN("en");

    private final String code;

    Language(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Language fromCode(String code) {
        return Arrays.stream(values())
                .filter(language -> language.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown language: " + code));
    }
}
