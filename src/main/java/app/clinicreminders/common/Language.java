package app.clinicreminders.common;

import java.util.Arrays;
import java.util.Locale;

/** Languages for patients, staff and clinics. Stored as the ISO code ({@code ar}, {@code en}). */
public enum Language {

    /** Lebanese Arabic: Levantine month names (تشرين الثاني), Western digits. */
    AR("ar", Locale.forLanguageTag("ar-LB")),
    EN("en", Locale.ENGLISH);

    private final String code;
    private final Locale locale;

    Language(String code, Locale locale) {
        this.code = code;
        this.locale = locale;
    }

    public String code() {
        return code;
    }

    /** For message bundles and date formatting. */
    public Locale locale() {
        return locale;
    }

    public static Language fromCode(String code) {
        return Arrays.stream(values())
                .filter(language -> language.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown language: " + code));
    }
}
