package app.clinicreminders.clinic;

import java.time.ZoneId;
import java.util.List;

import app.clinicreminders.common.Language;
import app.clinicreminders.common.NotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ClinicSettings {

    public static final int NAME_MAX = 100;

    private final ClinicRepository clinics;

    ClinicSettings(ClinicRepository clinics) {
        this.clinics = clinics;
    }

    @Transactional(readOnly = true)
    public Clinic get(long clinicId) {
        return clinics.findById(clinicId).orElseThrow(() -> new NotFoundException("Clinic", clinicId));
    }

    /** Every clinic, by name: the operator's list. */
    @Transactional(readOnly = true)
    public List<Clinic> all() {
        return clinics.findAllByOrderByNameAscIdAsc();
    }

    /**
     * Onboarding: a new clinic, not yet connected to WhatsApp.
     *
     * @throws IllegalArgumentException if the name is blank or longer than {@value #NAME_MAX} characters
     */
    public Clinic create(String name, ZoneId timezone, Language defaultLanguage) {
        return clinics.save(new Clinic(requireName(name), timezone, defaultLanguage));
    }

    /** The operator's edit. @throws IllegalArgumentException for a blank or too long name */
    public void updateDetails(long clinicId, String name, ZoneId timezone, Language defaultLanguage) {
        Clinic clinic = get(clinicId);
        clinic.rename(requireName(name));
        clinic.changeTimezone(timezone);
        clinic.changeDefaultLanguage(defaultLanguage);
    }

    /** @throws IllegalArgumentException if the address or hours are too long; nothing is saved */
    public void update(long clinicId, Language defaultLanguage, boolean h24Enabled, boolean h2Enabled,
                       @Nullable String address, @Nullable String hoursText) {
        Clinic clinic = get(clinicId);
        clinic.changeContactDetails(address, hoursText);
        clinic.changeDefaultLanguage(defaultLanguage);
        clinic.configureReminders(h24Enabled, h2Enabled);
    }

    private static String requireName(String name) {
        String stripped = name.strip();
        if (stripped.isEmpty() || stripped.length() > NAME_MAX) {
            throw new IllegalArgumentException("A clinic name is 1-" + NAME_MAX + " characters");
        }
        return stripped;
    }
}
