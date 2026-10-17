package app.clinicreminders.dashboard;

import java.time.Instant;

import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.common.Language;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.ScheduleSettings;
import app.clinicreminders.staff.StaffAccounts;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adding a doctor from the Doctors page, with a login when the owner or secretary asked for one,
 * in one transaction: a login that is refused (a taken username) adds no doctor either. And
 * deleting one, with their login and hours.
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
    private final AppointmentRepository appointments;

    DoctorSetup(ScheduleSettings scheduleSettings, StaffAccounts accounts, AppointmentRepository appointments) {
        this.scheduleSettings = scheduleSettings;
        this.accounts = accounts;
        this.appointments = appointments;
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

    /**
     * Deletes the doctor: their login and hours go, and they leave every list and booking; their past
     * appointments stay as history. Refused while appointments are still to come, so no patient is
     * left booked with a doctor who is gone.
     *
     * @return false, changing nothing, if the doctor still has appointments to come
     */
    @Transactional
    public boolean delete(long clinicId, long doctorId, Instant now) {
        if (appointments.countUpcomingOfDoctor(clinicId, doctorId, now) > 0) {
            return false;
        }
        accounts.deleteDoctorLogin(clinicId, doctorId);
        scheduleSettings.deleteDoctor(clinicId, doctorId, now);
        return true;
    }
}
