package app.clinicreminders.clinic;

import app.clinicreminders.common.Language;
import app.clinicreminders.common.NotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ClinicSettings {

    private final ClinicRepository clinics;

    ClinicSettings(ClinicRepository clinics) {
        this.clinics = clinics;
    }

    @Transactional(readOnly = true)
    public Clinic get(long clinicId) {
        return clinics.findById(clinicId).orElseThrow(() -> new NotFoundException("Clinic", clinicId));
    }

    /** @throws IllegalArgumentException if the address or hours are too long; nothing is saved */
    public void update(long clinicId, Language defaultLanguage, boolean h24Enabled, boolean h2Enabled,
                       @Nullable String address, @Nullable String hoursText) {
        Clinic clinic = get(clinicId);
        clinic.changeContactDetails(address, hoursText);
        clinic.changeDefaultLanguage(defaultLanguage);
        clinic.configureReminders(h24Enabled, h2Enabled);
    }
}
