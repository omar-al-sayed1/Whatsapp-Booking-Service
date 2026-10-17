package app.clinicreminders.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.time.ZoneId;
import java.util.List;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.Language;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.staff.StaffAccounts.NewPassword;
import app.clinicreminders.staff.StaffPrincipal;
import app.clinicreminders.staff.StaffRole;
import app.clinicreminders.staff.StaffUser;
import app.clinicreminders.staff.StaffUserRepository;
import app.clinicreminders.whatsapp.OutboundMessage.TemplateMessage;
import app.clinicreminders.whatsapp.TokenCipher;
import app.clinicreminders.whatsapp.WhatsAppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Onboarding a clinic through the operator pages (SPEC section 5.5). */
class OperatorPagesIT extends DashboardTest {

    private static final String TOKEN = "EAAG-secret-token-123";

    @Autowired
    ClinicRepository clinics;

    @Autowired
    StaffUserRepository users;

    @Autowired
    DoctorRepository doctors;

    @Autowired
    TokenCipher tokenCipher;

    private StaffPrincipal operator;
    private Clinic clinic;

    @BeforeEach
    void anOperatorAndAClinic() {
        operator = StaffPrincipal.of(users.save(StaffUser.operator("omar", "{noop}operator-pass", Language.EN)));
        clinic = data.clinic("Clinique Zahle");
    }

    // --- Clinics ---

    @Test
    void listsEveryClinicWithItsConnectionAndLogins() {
        data.connectedClinic("Dr. Haddad", "pn-h");
        staffOf(clinic, StaffRole.OWNER, Language.AR);

        assertThat(get(operator, "/operator")).hasStatusOk().bodyText()
                .contains("Clinique Zahle", "Dr. Haddad", "Asia/Beirut", "Not connected", "Connected");
    }

    @Test
    void createsAClinicAndOpensIt() {
        MvcTestResult result = post(operator, "/operator/clinics").param("name", " Dr. Saab ")
                .param("timezone", "Asia/Beirut").param("defaultLanguage", "en").exchange();

        Clinic created = clinics.findAllByOrderByNameAscIdAsc().stream()
                .filter(c -> c.getName().equals("Dr. Saab")).findFirst().orElseThrow();
        assertThat(result).hasStatus3xxRedirection().headers()
                .hasValue("Location", "/operator/clinics/" + created.getId());
        assertThat(created.getDefaultLanguage()).isEqualTo(Language.EN);
        assertThat(created.getWaPhoneNumberId()).isNull();
        assertThat(get(operator, "/operator/clinics/{id}", created.getId())).hasStatusOk().bodyText()
                .contains("Dr. Saab", "No token saved.", "No logins yet.");
    }

    @Test
    void refusesAnUnknownTimezoneOrABlankName() {
        assertThat(post(operator, "/operator/clinics").param("name", "X").param("timezone", "Beirut")
                .param("defaultLanguage", "ar")).flash().containsEntry("error", "error.timezone");
        assertThat(post(operator, "/operator/clinics").param("name", "  ").param("timezone", "Asia/Beirut")
                .param("defaultLanguage", "ar")).flash().containsEntry("error", "error.clinic-name");
        assertThat(clinics.findAllByOrderByNameAscIdAsc()).hasSize(1);
    }

    @Test
    void editsTheDetails() {
        post(operator, "/operator/clinics/{id}", clinic.getId()).param("name", "Clinique Chtaura")
                .param("timezone", "Europe/Paris").param("defaultLanguage", "en").exchange();

        Clinic edited = clinics.findById(clinic.getId()).orElseThrow();
        assertThat(edited.getName()).isEqualTo("Clinique Chtaura");
        assertThat(edited.getTimezone()).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(edited.getDefaultLanguage()).isEqualTo(Language.EN);
    }

    @Test
    void theAppointmentLengthIsTheOperatorsAndAppliesToEveryDoctor() {
        assertThat(clinic.getSlotMinutes()).as("default").isEqualTo(20);
        Doctor khoury = data.doctor(clinic, "Dr. Khoury", 20);
        Doctor saab = data.doctor(clinic, "Dr. Saab", 20);

        post(operator, "/operator/clinics/{id}", clinic.getId()).param("name", "Clinique Zahle")
                .param("timezone", "Asia/Beirut").param("defaultLanguage", "ar").param("slotMinutes", "30").exchange();

        assertThat(clinics.findById(clinic.getId()).orElseThrow().getSlotMinutes()).isEqualTo(30);
        assertThat(doctors.findByIdAndClinicId(khoury.getId(), clinic.getId()).orElseThrow().getSlotMinutes()).isEqualTo(30);
        assertThat(doctors.findByIdAndClinicId(saab.getId(), clinic.getId()).orElseThrow().getSlotMinutes()).isEqualTo(30);

        assertThat(post(operator, "/operator/clinics/{id}", clinic.getId()).param("name", "Clinique Zahle")
                .param("timezone", "Asia/Beirut").param("defaultLanguage", "ar").param("slotMinutes", "2"))
                .flash().containsEntry("error", "error.slot-length");
        assertThat(clinics.findById(clinic.getId()).orElseThrow().getSlotMinutes()).isEqualTo(30);
    }

    @Test
    void aNewClinicCanStartWithItsOwnLength() {
        post(operator, "/operator/clinics").param("name", "Dr. Saab").param("timezone", "Asia/Beirut")
                .param("defaultLanguage", "ar").param("slotMinutes", "15").exchange();

        assertThat(clinics.findAllByOrderByNameAscIdAsc()).filteredOn(c -> c.getName().equals("Dr. Saab")).singleElement()
                .extracting(Clinic::getSlotMinutes).isEqualTo(15);
    }

    @Test
    void anUnknownClinicIsNotFound() {
        assertThat(get(operator, "/operator/clinics/999")).hasStatus(404);
    }

    // --- WhatsApp connection ---

    @Test
    void theTokenIsStoredEncryptedAndNeverShownAgain() {
        connect("1065403", "1022901", TOKEN);

        Clinic saved = clinics.findById(clinic.getId()).orElseThrow();
        assertThat(saved.getWaPhoneNumberId()).isEqualTo("1065403");
        assertThat(saved.getWaBusinessAccountId()).isEqualTo("1022901");
        assertThat(new String(saved.getWaAccessTokenEncrypted())).doesNotContain(TOKEN);
        assertThat(tokenCipher.decrypt(clinic.getId(), saved.getWaAccessTokenEncrypted())).isEqualTo(TOKEN);
        assertThat(get(operator, "/operator/clinics/{id}", clinic.getId())).bodyText()
                .contains("1065403", "A token is saved.").doesNotContain(TOKEN);
    }

    @Test
    void aBlankTokenKeepsTheSavedOneAndRemovingItClearsIt() {
        connect("1065403", "1022901", TOKEN);
        connect("1065404", "1022901", "");

        Clinic kept = clinics.findById(clinic.getId()).orElseThrow();
        assertThat(kept.getWaPhoneNumberId()).isEqualTo("1065404");
        assertThat(tokenCipher.decrypt(clinic.getId(), kept.getWaAccessTokenEncrypted())).isEqualTo(TOKEN);

        post(operator, "/operator/clinics/{id}/whatsapp", clinic.getId()).param("phoneNumberId", "1065404")
                .param("businessAccountId", "1022901").param("removeToken", "true").exchange();
        assertThat(clinics.findById(clinic.getId()).orElseThrow().getWaAccessTokenEncrypted()).isNull();
    }

    @Test
    void anotherClinicsPhoneNumberIdIsRefused() {
        data.connectedClinic("Dr. Haddad", "1065403");

        assertThat(connect("1065403", "1022901", TOKEN)).flash().containsEntry("error", "error.phone-number-id-taken");
        Clinic unchanged = clinics.findById(clinic.getId()).orElseThrow();
        assertThat(unchanged.getWaPhoneNumberId()).isNull();
        assertThat(unchanged.getWaAccessTokenEncrypted()).isNull();
    }

    @Test
    void subscribesTheBusinessAccountToOurWebhooks() {
        assertThat(post(operator, "/operator/clinics/{id}/subscribe", clinic.getId()))
                .flash().containsEntry("error", "error.not-connected");

        connect("1065403", "1022901", TOKEN);
        assertThat(post(operator, "/operator/clinics/{id}/subscribe", clinic.getId()))
                .flash().containsEntry("notice", "notice.subscribed");
        assertThat(whatsApp.subscribedAccounts()).containsExactly("1022901");

        whatsApp.failNext(WhatsAppException.fromMetaError(200, "Permissions error"));
        assertThat(post(operator, "/operator/clinics/{id}/subscribe", clinic.getId()))
                .flash().containsEntry("metaError", "(#200) Permissions error");
    }

    // --- Test message ---

    @Test
    void sendsHelloWorldOrASampleReminderAndShowsTheResult() {
        connect("1065403", "1022901", TOKEN);
        data.doctor(clinic, "د. خوري", 30);

        assertThat(post(operator, "/operator/clinics/{id}/test", clinic.getId()).param("to", "03 123 456")
                .param("kind", "hello")).flash().extractingByKey("testOutcome")
                .hasFieldOrPropertyWithValue("result", "sent");
        assertThat(whatsApp.recent().getFirst().message())
                .isEqualTo(new TemplateMessage("+9613123456", "hello_world", "en_US", List.of()));

        assertThat(post(operator, "/operator/clinics/{id}/test", clinic.getId()).param("to", "+96170123456")
                .param("kind", "reminder")).hasStatus3xxRedirection();
        assertThat(whatsApp.recent().getFirst().message()).isInstanceOfSatisfying(TemplateMessage.class, reminder -> {
            assertThat(reminder.name()).isEqualTo("appointment_reminder");
            assertThat(reminder.languageCode()).isEqualTo("ar");
            assertThat(reminder.bodyParameters()).hasSize(4).startsWith("رنا", "د. خوري");
        });
        assertThat(jdbc.queryForObject("select count(*) from message_log where clinic_id = ?", Integer.class,
                clinic.getId())).isEqualTo(2);
    }

    @Test
    void aRefusedTestShowsMetasError() {
        connect("1065403", "1022901", TOKEN);
        whatsApp.failNext(WhatsAppException.fromMetaError(132001, "Template name does not exist in the translation"));

        assertThat(post(operator, "/operator/clinics/{id}/test", clinic.getId()).param("to", "+96170123456")
                .param("kind", "hello")).flash().extractingByKey("testOutcome")
                .hasFieldOrPropertyWithValue("result", "failed")
                .hasFieldOrPropertyWithValue("detail", "(#132001) Template name does not exist in the translation");
        assertThat(post(operator, "/operator/clinics/{id}/test", clinic.getId()).param("to", "hello")
                .param("kind", "hello")).flash().containsEntry("error", "error.phone");
    }

    // --- Logins ---

    @Test
    void aNewLoginGetsAPasswordShownOnceThatWorks() {
        MvcTestResult result = post(operator, "/operator/clinics/{id}/logins", clinic.getId())
                .param("username", "rana.sec").param("role", "SECRETARY").param("language", "ar").exchange();

        NewPassword password = (NewPassword) result.getMvcResult().getFlashMap().get("newPassword");
        assertThat(password.username()).isEqualTo("rana.sec");
        assertThat(password.password()).matches("[a-zA-Z2-9]{4}(-[a-zA-Z2-9]{4}){3}");
        assertThat(logIn("rana.sec", password.password())).isEqualTo("/");
        StaffUser saved = users.findByUsernameIgnoreCase("rana.sec").orElseThrow();
        assertThat(saved.getClinicId()).isEqualTo(clinic.getId());
        assertThat(saved.getRole()).isEqualTo(StaffRole.SECRETARY);
        assertThat(saved.getPasswordHash()).doesNotContain(password.password());
    }

    @Test
    void usernamesAreUniqueAcrossClinicsAndOperatorsCantBeAddedHere() {
        StaffPrincipal other = staffOf(data.clinic("B"), StaffRole.OWNER, Language.AR);

        assertThat(post(operator, "/operator/clinics/{id}/logins", clinic.getId()).param("username", other.username().toUpperCase())
                .param("role", "OWNER").param("language", "ar")).flash().containsEntry("error", "error.username-taken");
        assertThat(post(operator, "/operator/clinics/{id}/logins", clinic.getId()).param("username", "a b")
                .param("role", "OWNER").param("language", "ar")).flash().containsEntry("error", "error.username");
        assertThat(post(operator, "/operator/clinics/{id}/logins", clinic.getId()).param("username", "boss")
                .param("role", "OPERATOR").param("language", "ar")).flash().containsEntry("error", "error.username");
        assertThat(users.findByUsernameIgnoreCase("boss")).isEmpty();
    }

    @Test
    void resettingGivesANewPasswordAndDisablingStopsLogins() {
        NewPassword first = (NewPassword) post(operator, "/operator/clinics/{id}/logins", clinic.getId())
                .param("username", "rana.sec").param("role", "SECRETARY").param("language", "ar")
                .exchange().getMvcResult().getFlashMap().get("newPassword");
        long userId = users.findByUsernameIgnoreCase("rana.sec").orElseThrow().getId();

        NewPassword second = (NewPassword) post(operator, "/operator/clinics/{id}/logins/{u}/reset", clinic.getId(), userId)
                .exchange().getMvcResult().getFlashMap().get("newPassword");
        assertThat(logIn("rana.sec", first.password())).isEqualTo("/login?error");
        assertThat(logIn("rana.sec", second.password())).isEqualTo("/");

        post(operator, "/operator/clinics/{id}/logins/{u}/enabled", clinic.getId(), userId).param("enabled", "false").exchange();
        assertThat(logIn("rana.sec", second.password())).isEqualTo("/login?error");
        post(operator, "/operator/clinics/{id}/logins/{u}/enabled", clinic.getId(), userId).param("enabled", "true").exchange();
        assertThat(logIn("rana.sec", second.password())).isEqualTo("/");
    }

    @Test
    void anotherClinicsLoginCantBeResetThroughThisClinic() {
        StaffPrincipal other = staffOf(data.clinic("B"), StaffRole.OWNER, Language.AR);

        assertThat(post(operator, "/operator/clinics/{id}/logins/{u}/reset", clinic.getId(), other.id())).hasStatus(404);
        assertThat(post(operator, "/operator/clinics/{id}/logins/{u}/enabled", clinic.getId(), other.id())
                .param("enabled", "false")).hasStatus(404);
    }

    // --- Own password ---

    @Test
    void everyoneCanChangeTheirOwnPassword() {
        StaffPrincipal secretary = secretaryOf(clinic);

        assertThat(post(secretary, "/account/password").param("currentPassword", "wrong").param("newPassword", "long-enough-1"))
                .flash().containsEntry("error", "error.password-wrong");
        assertThat(post(secretary, "/account/password").param("currentPassword", "secret").param("newPassword", "short"))
                .flash().containsEntry("error", "error.password-short");
        assertThat(post(secretary, "/account/password").param("currentPassword", "secret").param("newPassword", "long-enough-1"))
                .flash().containsEntry("notice", "notice.password-changed");
        assertThat(logIn(secretary.username(), "long-enough-1")).isEqualTo("/");

        assertThat(get(operator, "/account/password")).hasStatusOk();
        post(operator, "/account/password").param("currentPassword", "operator-pass").param("newPassword", "operator-pass-2")
                .exchange();
        assertThat(logIn("omar", "operator-pass-2")).isEqualTo("/");
    }

    @Test
    void operatorsCanSwitchLanguageToo() {
        assertThat(post(operator, "/language").param("lang", "ar").header("Referer", "http://localhost/operator"))
                .hasStatus3xxRedirection().headers().hasValue("Location", "/operator");
        assertThat(users.findByUsernameIgnoreCase("omar").orElseThrow().getLanguage()).isEqualTo(Language.AR);
    }

    private MvcTestResult connect(String phoneNumberId, String businessAccountId, String token) {
        return post(operator, "/operator/clinics/{id}/whatsapp", clinic.getId()).param("phoneNumberId", phoneNumberId)
                .param("businessAccountId", businessAccountId).param("accessToken", token).exchange();
    }

    /** @return where the login sent the browser: "/" on success, "/login?error" otherwise */
    private String logIn(String username, String password) {
        return mvc.post().uri("/login").param("username", username).param("password", password).with(csrf())
                .exchange().getResponse().getHeader("Location");
    }
}
