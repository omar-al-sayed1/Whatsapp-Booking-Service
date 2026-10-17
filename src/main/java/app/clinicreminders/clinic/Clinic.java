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

    /** Every doctor's appointment length unless the operator changes it. */
    public static final int DEFAULT_SLOT_MINUTES = 20;

    /** Address and hours each; keeps the Info reply well inside WhatsApp's 4096-character text limit. */
    public static final int CONTACT_TEXT_MAX = 500;

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

    /** The length of every appointment slot, for all the clinic's doctors. Set by the operator. */
    @Column(name = "slot_minutes")
    private int slotMinutes = DEFAULT_SLOT_MINUTES;

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

    /**
     * The operator's edit of the WhatsApp ids; blank ones are cleared. The token is kept: see
     * {@link #changeAccessToken}.
     */
    public void changeWhatsAppIds(@Nullable String phoneNumberId, @Nullable String businessAccountId) {
        this.waPhoneNumberId = phoneNumberId;
        this.waBusinessAccountId = businessAccountId;
    }

    /** Encrypted with {@code TokenCipher} for this clinic's id; null removes the token. */
    public void changeAccessToken(byte @Nullable [] encryptedAccessToken) {
        this.waAccessTokenEncrypted = encryptedAccessToken;
    }

    public void rename(String name) {
        this.name = name;
    }

    /** Stored times stay as they are; they are shown and scheduled in the new zone from now on. */
    public void changeTimezone(ZoneId timezone) {
        this.timezone = timezone;
    }

    /** Applies to reminders planned from now on; already planned ones are re-checked when due. */
    public void configureReminders(boolean h24Enabled, boolean h2Enabled) {
        this.reminderH24Enabled = h24Enabled;
        this.reminderH2Enabled = h2Enabled;
    }

    /**
     * What patients get when they tap Info on WhatsApp. Blank clears a field.
     *
     * @throws IllegalArgumentException if either is longer than {@value #CONTACT_TEXT_MAX} characters
     */
    public void changeContactDetails(@Nullable String address, @Nullable String hoursText) {
        String newAddress = contactText(address);
        String newHours = contactText(hoursText);
        this.address = newAddress;
        this.hoursText = newHours;
    }

    private static @Nullable String contactText(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() > CONTACT_TEXT_MAX) {
            throw new IllegalArgumentException("At most " + CONTACT_TEXT_MAX + " characters");
        }
        return stripped;
    }

    /** The language new patients get until they choose. */
    public void changeDefaultLanguage(Language language) {
        this.defaultLanguage = language;
    }

    /**
     * The operator's setting; {@code ScheduleSettings} applies it to every doctor of the clinic.
     *
     * @throws IllegalArgumentException outside 5 to 240 minutes
     */
    public void changeSlotMinutes(int minutes) {
        if (minutes < 5 || minutes > 240) {
            throw new IllegalArgumentException("Slot length must be 5-240 minutes");
        }
        this.slotMinutes = minutes;
    }

    public int getSlotMinutes() {
        return slotMinutes;
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
