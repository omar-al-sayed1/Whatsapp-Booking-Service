package app.clinicreminders.patient;

import java.time.Instant;

import app.clinicreminders.common.Language;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;

/**
 * Privacy: name, phone, language and opt-out only. Never add medical notes, diagnoses or reasons
 * for visit.
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
        this.name = name;
        this.phoneE164 = phoneE164;
        this.language = language;
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
