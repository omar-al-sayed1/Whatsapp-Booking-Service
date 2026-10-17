package app.clinicreminders.dashboard;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Initials and a stable colour for a person's avatar, used in templates as
 * {@code ${@avatars.initials(name)}} and {@code ${@avatars.hue(name)}}.
 */
@Component("avatars")
public class Avatars {

    /** U+200C ZERO WIDTH NON-JOINER. */
    private static final String NON_JOINER = String.valueOf((char) 0x200C);

    /** Titles skipped so "Dr. Rana Haddad" and "د. رنا حداد" both give two initials, not "D" and "د". */
    private static final Set<String> TITLES = Set.of("dr", "dr.", "prof", "prof.", "mr", "mr.", "mrs", "mrs.", "ms", "ms.",
            "د", "د.", "الدكتور", "الدكتورة", "دكتور", "دكتورة");

    public String initials(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        List<String> words = Arrays.stream(name.strip().split("\\s+"))
                .filter(word -> !TITLES.contains(word.toLowerCase(Locale.ROOT)))
                .toList();
        if (words.isEmpty()) {
            words = List.of(name.strip());
        }
        String first = firstLetter(words.getFirst());
        if (words.size() == 1) {
            return first;
        }
        String last = firstLetter(words.getLast());
        // Arabic letters side by side join into one shape; a zero-width non-joiner keeps them apart.
        return isArabic(first) && isArabic(last) ? first + NON_JOINER + last : first + last;
    }

    /** 0-359, the same for the same name, so a patient keeps their colour everywhere. */
    public int hue(@Nullable String name) {
        return name == null ? 210 : Math.floorMod(name.strip().hashCode() * 31, 360);
    }

    private static boolean isArabic(String letter) {
        return Character.UnicodeScript.of(letter.codePointAt(0)) == Character.UnicodeScript.ARABIC;
    }

    private static String firstLetter(String word) {
        return new String(Character.toChars(word.codePointAt(0))).toUpperCase(Locale.ROOT);
    }
}
