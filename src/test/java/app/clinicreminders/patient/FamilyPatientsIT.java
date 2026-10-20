package app.clinicreminders.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.list;

import java.time.Instant;
import java.util.List;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.booking.Conversation;
import app.clinicreminders.booking.ConversationRepository;
import app.clinicreminders.booking.ConversationState;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.common.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** A parent and their children on one phone: several patients of a clinic share a number. */
class FamilyPatientsIT extends IntegrationTest {

    private static final String PHONE = "+96170123456";

    @Autowired
    PatientRepository patients;

    @Autowired
    PatientService patientService;

    @Autowired
    ConversationRepository conversations;

    private Clinic clinic;
    private Clinic other;
    private Patient mira;
    private Patient karim;

    @BeforeEach
    void aFamilyOnOneNumber() {
        clinic = data.clinic("A");
        other = data.clinic("B");
        mira = data.patient(clinic, "Mira Haddad", PHONE, Language.AR);
        karim = data.patient(clinic, "Karim Haddad", PHONE, Language.AR);
        data.patient(other, "Someone Else", PHONE, Language.EN);
    }

    @Test
    void theNumbersPatientsAreFoundTogetherOnlyInTheirClinic() {
        assertThat(patients.findByClinicIdAndPhoneE164OrderById(clinic.getId(), PHONE)).extracting(Patient::getName)
                .containsExactly("Mira Haddad", "Karim Haddad");
        assertThat(patients.findByClinicIdAndPhoneE164OrderById(other.getId(), PHONE))
                .extracting(Patient::getName).containsExactly("Someone Else");
        assertThat(patients.findByClinicIdAndPhoneE164InOrderByName(clinic.getId(), List.of(PHONE, "+96171000000")))
                .extracting(Patient::getName).containsExactly("Karim Haddad", "Mira Haddad");
    }

    @Test
    void theyShareTheWhatsAppUserId() {
        mira.linkWhatsAppUser("LB.1");
        karim.linkWhatsAppUser("LB.1");
        patients.save(mira);
        patients.save(karim);

        assertThat(patients.findByClinicIdAndWaUserIdOrderById(clinic.getId(), "LB.1")).extracting(Patient::getId)
                .containsExactly(mira.getId(), karim.getId());
        assertThat(patients.findByClinicIdAndWaUserIdOrderById(other.getId(), "LB.1")).isEmpty();
    }

    @Test
    void optingOutIsTheNumbers() {
        assertThat(patients.existsByClinicIdAndPhoneE164AndOptedOutTrue(clinic.getId(), PHONE)).isFalse();
        karim.optOut();
        patients.save(karim);

        assertThat(patients.existsByClinicIdAndPhoneE164AndOptedOutTrue(clinic.getId(), PHONE)).isTrue();
        assertThat(patients.existsByClinicIdAndPhoneE164AndOptedOutTrue(other.getId(), PHONE)).isFalse();
        assertThat(patientService.findOrCreate(clinic.getId(), PHONE, "Lina Haddad", Language.AR).isOptedOut())
                .as("a new family member on a number that opted out").isTrue();
    }

    @Test
    void aNameIsMatchedInAnyCaseAndANewOneAddsAFamilyMember() {
        assertThat(patientService.findOrCreate(clinic.getId(), PHONE, "  karim   HADDAD ", Language.EN).getId()).isEqualTo(karim.getId());

        Patient lina = patientService.findOrCreate(clinic.getId(), PHONE, " Lina  Haddad", Language.EN);

        assertThat(lina.getName()).isEqualTo("Lina Haddad");
        assertThat(lina.getLanguage()).isEqualTo(Language.EN);
        assertThat(patientService.household(clinic.getId(), PHONE)).extracting(Patient::getId)
                .containsExactly(mira.getId(), karim.getId(), lina.getId());
        assertThat(patientService.households(clinic.getId(), List.of(PHONE))).containsOnlyKeys(PHONE)
                .extractingByKey(PHONE).asInstanceOf(list(Patient.class))
                .extracting(Patient::getName).containsExactly("Karim Haddad", "Lina Haddad", "Mira Haddad");
    }

    @Test
    void twoPatientsOfANumberCannotHaveTheSameName() {
        assertThatThrownBy(() -> data.patient(clinic, "MIRA HADDAD", PHONE, Language.AR))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingOneKeepsTheNumbersConversationForTheOthers() {
        conversations.save(new Conversation(clinic.getId(), PHONE, ConversationState.MENU, Instant.parse("2026-10-14T09:30:00Z")));

        patientService.delete(clinic.getId(), karim.getId());
        assertThat(jdbc.queryForObject("select count(*) from conversation", Integer.class)).isEqualTo(1);

        patientService.delete(clinic.getId(), mira.getId());
        assertThat(jdbc.queryForObject("select count(*) from conversation", Integer.class)).as("the last one").isZero();
    }
}
