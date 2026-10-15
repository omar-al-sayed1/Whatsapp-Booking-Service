package app.clinicreminders.staff;

import java.time.Instant;

import app.clinicreminders.common.Language;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;

/** A dashboard login. Operators have no clinic; everyone else belongs to exactly one. */
@Entity
@Table(name = "staff_user")
public class StaffUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id")
    private Long clinicId;

    private String username;

    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    private StaffRole role;

    private Language language;

    private boolean enabled = true;

    @Generated
    @Column(name = "created_at")
    private Instant createdAt;

    protected StaffUser() {
    }

    private StaffUser(Long clinicId, String username, String passwordHash, StaffRole role, Language language) {
        this.clinicId = clinicId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.language = language;
    }

    public static StaffUser operator(String username, String passwordHash, Language language) {
        return new StaffUser(null, username, passwordHash, StaffRole.OPERATOR, language);
    }

    public static StaffUser clinicStaff(long clinicId, String username, String passwordHash, StaffRole role,
                                        Language language) {
        if (role == StaffRole.OPERATOR) {
            throw new IllegalArgumentException("Operators don't belong to a clinic");
        }
        return new StaffUser(clinicId, username, passwordHash, role, language);
    }

    /** The dashboard's language for this user. */
    public void changeLanguage(Language language) {
        this.language = language;
    }

    public Long getId() {
        return id;
    }

    public Long getClinicId() {
        return clinicId;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public StaffRole getRole() {
        return role;
    }

    public Language getLanguage() {
        return language;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
