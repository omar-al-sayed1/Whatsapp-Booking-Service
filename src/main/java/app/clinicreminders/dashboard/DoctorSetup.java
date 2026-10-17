package app.clinicreminders.dashboard;

import app.clinicreminders.common.Language;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.ScheduleSettings;
import app.clinicreminders.staff.StaffAccounts;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adding a doctor from the Doctors page, with a login when the owner or secretary asked for one,
 * in one transaction: a login that is refused (a taken username) adds no doctor either.
 */
@Service
public class DoctorSetup {

    /** The login to make for the new doctor, as typed by the owner or secretary. */
    public record Login(String username, String password, Language language) {

        @Override
        public String toString() {
            return "Login[username=" + username + ", password=<redacted>, language=" + language + "]";
        }
    }

    private final ScheduleSettings scheduleSettings;
    private final StaffAccounts accounts;

    DoctorSetup(ScheduleSettings scheduleSettings, StaffAccounts accounts) {
        this.scheduleSettings = scheduleSettings;
        this.accounts = accounts;
    }

    /**
     * @throws IllegalArgumentException for an empty name, or a login whose username or password isn't valid
     * @throws app.clinicreminders.staff.UsernameTakenException if the username is taken
     */
    @Transactional
    public Doctor add(long clinicId, String displayName, @Nullable Login login) {
        Doctor doctor = scheduleSettings.addDoctor(clinicId, displayName);
        if (login != null) {
            accounts.createDoctorLogin(clinicId, doctor.getId(), login.username(), login.password(), login.language());
        }
        return doctor;
    }
}
