package app.clinicreminders.clinic;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import app.clinicreminders.common.Language;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.jspecify.annotations.Nullable;

/** The tenant. Every tenant-owned row carries this clinic's id. */
@Entity
@Table(name = "clinic")
public class Clinic {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Beirut");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private ZoneId timezone;

    @Column(name = "default_language")
    private Language defaultLanguage;

    private String address;

    @Column(name = "hours_text")
    private String hoursText;

    @Column(name = "wa_phone_number_id")
    private String waPhoneNumberId;

    @Column(name = "wa_business_account_id")
    private String waBusinessAccountId;

    /** AES-GCM ciphertext; never the plain token. */
    @Column(name = "wa_access_token_encrypted")
    private byte[] waAccessTokenEncrypted;

    @Column(name = "quiet_hours_start")
    private LocalTime quietHoursStart = LocalTime.of(21, 0);

    @Column(name = "quiet_hours_end")
    private LocalTime quietHoursEnd = LocalTime.of(8, 0);

    @Column(name = "reminder_h24_enabled")
    private boolean reminderH24Enabled = true;

    @Column(name = "reminder_h2_enabled")
    private boolean reminderH2Enabled = true;

    @Generated
    @Column(name = "created_at")
    private Instant createdAt;

    protected Clinic() {
    }

    public Clinic(String name, ZoneId timezone, Language defaultLanguage) {
        this.name = name;
        this.timezone = timezone;
        this.defaultLanguage = defaultLanguage;
    }

    /**
     * Links the clinic to its WhatsApp number. The token must already be encrypted with
     * {@code TokenCipher} for this clinic's id; null leaves the clinic without a token.
     */
    public void connectWhatsApp(String phoneNumberId, String businessAccountId, byte @Nullable [] encryptedAccessToken) {
        this.waPhoneNumberId = phoneNumberId;
        this.waBusinessAccountId = businessAccountId;
        this.waAccessTokenEncrypted = encryptedAccessToken;
    }

    /** Applies to reminders planned from now on; already planned ones are re-checked when due. */
    public void configureReminders(boolean h24Enabled, boolean h2Enabled) {
        this.reminderH24Enabled = h24Enabled;
        this.reminderH2Enabled = h2Enabled;
    }

    /** The language new patients get until they choose. */
    public void changeDefaultLanguage(Language language) {
        this.defaultLanguage = language;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public ZoneId getTimezone() {
        return timezone;
    }

    public Language getDefaultLanguage() {
        return defaultLanguage;
    }

    public String getAddress() {
        return address;
    }

    public String getHoursText() {
        return hoursText;
    }

    public String getWaPhoneNumberId() {
        return waPhoneNumberId;
    }

    public String getWaBusinessAccountId() {
        return waBusinessAccountId;
    }

    public byte[] getWaAccessTokenEncrypted() {
        return waAccessTokenEncrypted;
    }

    public LocalTime getQuietHoursStart() {
        return quietHoursStart;
    }

    public LocalTime getQuietHoursEnd() {
        return quietHoursEnd;
    }

    public boolean isReminderH24Enabled() {
        return reminderH24Enabled;
    }

    public boolean isReminderH2Enabled() {
        return reminderH2Enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
