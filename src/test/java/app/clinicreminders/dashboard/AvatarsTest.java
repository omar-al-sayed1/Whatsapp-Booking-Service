package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AvatarsTest {

    private final Avatars avatars = new Avatars();

    @Test
    void initialsSkipTitlesInBothLanguages() {
        assertThat(avatars.initials("Rana Haddad")).isEqualTo("RH");
        assertThat(avatars.initials("Dr. Marie-Thérèse Abou Jaoudeh")).isEqualTo("MJ");
        assertThat(avatars.initials("د. خوري")).isEqualTo("خ");
        assertThat(avatars.initials("رنا حداد")).as("kept apart, not joined").isEqualTo("ر" + (char) 0x200C + "ح");
        assertThat(avatars.initials("karim")).isEqualTo("K");
    }

    @Test
    void oddNamesStillGetSomething() {
        assertThat(avatars.initials(null)).isEqualTo("?");
        assertThat(avatars.initials("  ")).isEqualTo("?");
        assertThat(avatars.initials("Dr.")).isEqualTo("D");
    }

    @Test
    void theColourIsStableAndInRange() {
        assertThat(avatars.hue("Rana Haddad")).isEqualTo(avatars.hue(" Rana Haddad ")).isBetween(0, 359);
        assertThat(avatars.hue(null)).isEqualTo(210);
    }
}
