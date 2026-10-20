package app.clinicreminders.patient;

import java.time.Instant;
import java.util.Locale;

import app.clinicreminders.common.Language;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;

/**
 * Privacy: name, phone, WhatsApp user id, language and opt-out only. Never add medical notes,
 * diagnoses or reasons for visit.
 *
 * <p>Several patients may share a phone (a parent and their children), each under their own name;
 * no two of a clinic share both. Opting out is the number's: every patient on it is opted out.
 */
@Entity
@Table(name = "patient")
public class Patient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id")
    private Long clinicId;

    private String name;

    @Column(name = "phone_e164")
    private String phoneE164;

    /** Meta's business-scoped user id (BSUID), learned from webhooks. */
    @Column(name = "wa_user_id")
    private String waUserId;

    private Language language;

    @Column(name = "opted_out")
    private boolean optedOut;

    @Generated
    @Column(name = "created_at")
    private Instant createdAt;

    protected Patient() {
    }

    public Patient(long clinicId, String name, String phoneE164, Language language) {
        this.clinicId = clinicId;
        this.name = normalizeName(name);
        this.phoneE164 = phoneE164;
        this.language = language;
    }

    /** Replied STOP: never message this patient again. */
    public void optOut() {
        this.optedOut = true;
    }

    /** Whether this is the patient called {@code name}: any case, extra spaces ignored. */
    public boolean hasName(String name) {
        return nameKey(this.name).equals(nameKey(name));
    }

    /** A name as stored: trimmed, with single spaces. */
    public static String normalizeName(String name) {
        return name.strip().replaceAll("\\s+", " ");
    }

    private static String nameKey(String name) {
        return normalizeName(name).toLowerCase(Locale.ROOT);
    }

    /** Meta regenerates the id when the user changes number, so the latest one wins. */
    public void linkWhatsAppUser(String waUserId) {
        this.waUserId = waUserId;
    }

    public Long getId() {
        return id;
    }

    public Long getClinicId() {
        return clinicId;
    }

    public String getName() {
        return name;
    }

    public String getPhoneE164() {
        return phoneE164;
    }

    public String getWaUserId() {
        return waUserId;
    }

    public Language getLanguage() {
        return language;
    }

    public boolean isOptedOut() {
        return optedOut;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
