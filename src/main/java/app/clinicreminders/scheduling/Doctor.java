package app.clinicreminders.scheduling;

import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;

@Entity
@Table(name = "doctor")
public class Doctor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id")
    private Long clinicId;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "slot_minutes")
    private int slotMinutes;

    private boolean active = true;

    @Generated
    @Column(name = "created_at")
    private Instant createdAt;

    protected Doctor() {
    }

    public Doctor(long clinicId, String displayName, int slotMinutes) {
        this.clinicId = clinicId;
        this.displayName = displayName;
        this.slotMinutes = slotMinutes;
    }

    /** Existing appointments keep their own start and end; only new slots use the new length. */
    public void changeSlotMinutes(int slotMinutes) {
        this.slotMinutes = slotMinutes;
    }

    public void rename(String displayName) {
        this.displayName = displayName;
    }

    /** Inactive doctors get no new slots; their existing appointments stay. */
    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }

    public Long getId() {
        return id;
    }

    public Long getClinicId() {
        return clinicId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getSlotMinutes() {
        return slotMinutes;
    }

    public Duration slotLength() {
        return Duration.ofMinutes(slotMinutes);
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
