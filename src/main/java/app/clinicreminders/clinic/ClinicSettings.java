package app.clinicreminders.clinic;

import app.clinicreminders.common.Language;
import app.clinicreminders.common.NotFoundException;
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

    public void update(long clinicId, Language defaultLanguage, boolean h24Enabled, boolean h2Enabled) {
        Clinic clinic = get(clinicId);
        clinic.changeDefaultLanguage(defaultLanguage);
        clinic.configureReminders(h24Enabled, h2Enabled);
    }
}
