package app.clinicreminders.whatsapp.webhook;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Recognises an opt-out: a message that is just STOP or توقف, ignoring case, surrounding
 * punctuation, emoji, Arabic diacritics and tatweel. "Stop the reminders please" is not an
 * opt-out; it is left for the secretary.
 */
public final class OptOutWords {

    private static final Set<String> WORDS = Set.of("stop", "توقف");
    private static final Pattern ARABIC_MARKS = Pattern.compile("[\\u064B-\\u0652\\u0640]");
    private static final Pattern NOISE = Pattern.compile("[\\p{P}\\p{S}\\s]+");

    private OptOutWords() {
    }

    public static boolean isOptOut(String text) {
        return WORDS.contains(normalize(text));
    }

    /** True for the Arabic word, so a new patient created by it gets Arabic as their language. */
    public static boolean isArabic(String text) {
        return normalize(text).equals("توقف");
    }

    static String normalize(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        normalized = ARABIC_MARKS.matcher(normalized).replaceAll("");
        normalized = NOISE.matcher(normalized).replaceAll(" ");
        return normalized.trim().toLowerCase(Locale.ROOT);
    }
}
