package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import org.junit.jupiter.api.Test;

class OperatorControllerTest {

    @Test
    void aNewClinicHasOnlyItsDetails() {
        Clinic clinic = new Clinic("A", Clinic.DEFAULT_ZONE, Language.AR);

        assertThat(OperatorController.readySteps(clinic, 0)).isEqualTo(1);
    }

    @Test
    void everyStepCounts() {
        Clinic clinic = new Clinic("A", Clinic.DEFAULT_ZONE, Language.AR);
        clinic.changeWhatsAppIds("1065403", null);
        assertThat(OperatorController.readySteps(clinic, 0)).isEqualTo(2);

        clinic.changeWhatsAppIds("1065403", "1022901");
        clinic.changeAccessToken(new byte[] {1});
        assertThat(OperatorController.readySteps(clinic, 2)).isEqualTo(OperatorController.SETUP_STEPS);
    }
}
