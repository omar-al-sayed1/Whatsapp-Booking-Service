package app.clinicreminders.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import app.clinicreminders.IntegrationTest;
import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Every query added in M2, each with its clinic-isolation case. */
class WhatsAppRepositoriesIT extends IntegrationTest {

    @Autowired
    MessageLogRepository logs;

    @Autowired
    PatientRepository patients;

    @Autowired
    ClinicRepository clinics;

    @Autowired
    MessageRateRepository rates;

    private Clinic clinicA;
    private Clinic clinicB;

    @BeforeEach
    void twoClinics() {
        clinicA = data.connectedClinic("A", "pn-a");
        clinicB = data.connectedClinic("B", "pn-b");
    }

    @Test
    void aMessageIsFoundOnlyInItsClinic() {
        logs.save(MessageLog.sent(clinicA.getId(), null, MessageKind.TEXT, null, "wamid.A", "service", null));

        assertThat(logs.findByClinicIdAndWaMessageId(clinicA.getId(), "wamid.A")).isPresent();
        assertThat(logs.findByClinicIdAndWaMessageId(clinicB.getId(), "wamid.A")).isEmpty();
    }

    @Test
    void anInboundMessageIsInsertedOnlyOnce() {
        var first = logs.insertInboundIfNew(clinicA.getId(), "wamid.IN-1", "TEXT");
        var again = logs.insertInboundIfNew(clinicA.getId(), "wamid.IN-1", "TEXT");

        assertThat(first).isPresent();
        assertThat(again).isEmpty();
        MessageLog row = logs.findByClinicIdAndWaMessageId(clinicA.getId(), "wamid.IN-1").orElseThrow();
        assertThat(row.getId()).isEqualTo(first.get());
        assertThat(row.getDirection()).isEqualTo(MessageDirection.IN);
        assertThat(logs.findByClinicIdAndWaMessageId(clinicB.getId(), "wamid.IN-1")).isEmpty();
    }

    @Test
    void patientsAreFoundByPhoneOrWhatsAppUserOnlyInTheirClinic() {
        Patient patient = data.patient(clinicA, "+96170123456");
        patient.linkWhatsAppUser("LB.1");
        patients.save(patient);

        assertThat(patients.findByClinicIdAndPhoneE164(clinicA.getId(), "+96170123456")).isPresent();
        assertThat(patients.findByClinicIdAndPhoneE164(clinicB.getId(), "+96170123456")).isEmpty();
        assertThat(patients.findByClinicIdAndWaUserId(clinicA.getId(), "LB.1")).isPresent();
        assertThat(patients.findByClinicIdAndWaUserId(clinicB.getId(), "LB.1")).isEmpty();
    }

    @Test
    void aClinicIsFoundByItsWhatsAppNumber() {
        data.clinic("Not connected");

        assertThat(clinics.findByWaPhoneNumberId("pn-b")).get().extracting(Clinic::getId).isEqualTo(clinicB.getId());
        assertThat(clinics.findByWaPhoneNumberId("pn-unknown")).isEmpty();
        assertThat(clinics.findByWaPhoneNumberIdNotNullOrderByName()).extracting(Clinic::getName).containsExactly("A", "B");
    }

    @Test
    void theRateInForceIsTheLatestNotInTheFuture() {
        data.rate("Rest of Middle East", "utility", "0.0100", LocalDate.of(2026, 7, 1));
        data.rate("Rest of Middle East", "utility", "0.0110", LocalDate.of(2026, 10, 1));
        data.rate("Rest of Middle East", "utility", "0.0120", LocalDate.of(2027, 1, 1));
        data.rate("Rest of Middle East", "service", "0.0080", LocalDate.of(2026, 10, 1));

        assertThat(rates.findInForce("Rest of Middle East", "utility", LocalDate.of(2026, 10, 15)))
                .get().extracting(MessageRate::getUsdPerMessage).satisfies(usd -> assertThat(usd).isEqualByComparingTo("0.0110"));
        assertThat(rates.findInForce("Rest of Middle East", "utility", LocalDate.of(2026, 6, 30))).isEmpty();
    }
}
