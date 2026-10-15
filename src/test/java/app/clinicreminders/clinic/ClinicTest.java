package app.clinicreminders.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import app.clinicreminders.common.Language;
import org.junit.jupiter.api.Test;

class ClinicTest {

    private final Clinic clinic = new Clinic("A", Clinic.DEFAULT_ZONE, Language.AR);

    @Test
    void contactDetailsAreStrippedAndBlankMeansNone() {
        clinic.changeContactDetails("  Main street, Zahle\n", " ");

        assertThat(clinic.getAddress()).isEqualTo("Main street, Zahle");
        assertThat(clinic.getHoursText()).isNull();
    }

    @Test
    void contactDetailsHaveALimit() {
        clinic.changeContactDetails("x".repeat(Clinic.CONTACT_TEXT_MAX), null);
        assertThat(clinic.getAddress()).hasSize(Clinic.CONTACT_TEXT_MAX);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> clinic.changeContactDetails("Zahle", "x".repeat(Clinic.CONTACT_TEXT_MAX + 1)));
        assertThat(clinic.getAddress()).as("unchanged").hasSize(Clinic.CONTACT_TEXT_MAX);
    }
}
