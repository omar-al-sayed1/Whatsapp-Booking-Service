package app.clinicreminders.staff;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import app.clinicreminders.common.Language;
import app.clinicreminders.common.NotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dashboard logins. The operator creates clinic logins and resets their passwords; passwords are
 * generated and shown once, never chosen by the operator. A doctor's login is made by the clinic's
 * owner or secretary, who choose its username and password. Everyone can then change their own.
 */
@Service
@Transactional
public class StaffAccounts {

    /** Letters, digits, dot, dash and underscore; what people can type on a phone keyboard without trouble. */
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,40}");
    public static final int MIN_PASSWORD_LENGTH = 10;

    /** A password to hand over once. Never logged: see {@link #toString()}. */
    public record NewPassword(String username, String password) {

        @Override
        public String toString() {
            return "NewPassword[username=" + username + ", password=<redacted>]";
        }
    }

    public enum PasswordChange { CHANGED, WRONG_CURRENT_PASSWORD, TOO_SHORT }

    private final StaffUserRepository users;
    private final PasswordEncoder passwordEncoder;

    StaffAccounts(StaffUserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * @throws IllegalArgumentException if the username isn't 3-40 letters, digits, dots, dashes or underscores
     * @throws UsernameTakenException   if any clinic already has it, ignoring case
     */
    public NewPassword createClinicLogin(long clinicId, String username, StaffRole role, Language language) {
        String name = requireFree(username);
        String password = GeneratedPasswords.generate();
        users.save(StaffUser.clinicStaff(clinicId, name, passwordEncoder.encode(password), role, language));
        return new NewPassword(name, password);
    }

    /**
     * A login for one of the clinic's doctors, with the username and password the owner or secretary
     * chose. The database refuses a doctor of another clinic, and a second login for the same doctor.
     *
     * @throws IllegalArgumentException if the username isn't valid or the password too short ({@link #isLongEnough})
     * @throws UsernameTakenException   if any clinic already has the username, ignoring case
     */
    public StaffUser createDoctorLogin(long clinicId, long doctorId, String username, String password, Language language) {
        String name = requireFree(username);
        requireLongEnough(password);
        return users.save(StaffUser.doctor(clinicId, doctorId, name, passwordEncoder.encode(password), language));
    }

    @Transactional(readOnly = true)
    public Optional<StaffUser> doctorLogin(long clinicId, long doctorId) {
        return users.findByClinicIdAndDoctorId(clinicId, doctorId);
    }

    /** @throws IllegalArgumentException if the password is too short ({@link #isLongEnough}) */
    public void setDoctorPassword(long clinicId, long doctorId, String password) {
        requireLongEnough(password);
        doctorUser(clinicId, doctorId).changePasswordHash(passwordEncoder.encode(password));
    }

    /** When the doctor is deleted: their login goes with them (a login has no history to keep). */
    public void deleteDoctorLogin(long clinicId, long doctorId) {
        users.findByClinicIdAndDoctorId(clinicId, doctorId).ifPresent(users::delete);
    }

    /** As {@link #setEnabled}, for a doctor's login. */
    public void setDoctorLoginEnabled(long clinicId, long doctorId, boolean enabled) {
        doctorUser(clinicId, doctorId).setEnabled(enabled);
    }

    /** 3-40 letters, digits, dots, dashes or underscores (whether it's free is another matter). */
    public static boolean isValidUsername(String username) {
        return USERNAME.matcher(username.strip()).matches();
    }

    /** At least {@link #MIN_PASSWORD_LENGTH} characters, not counting spaces around them. */
    public static boolean isLongEnough(String password) {
        return password.strip().length() >= MIN_PASSWORD_LENGTH;
    }

    public NewPassword resetPassword(long clinicId, long userId) {
        StaffUser user = clinicUser(clinicId, userId);
        String password = GeneratedPasswords.generate();
        user.changePasswordHash(passwordEncoder.encode(password));
        return new NewPassword(user.getUsername(), password);
    }

    /** A disabled login can't sign in. A session that is already open lasts until it times out. */
    public void setEnabled(long clinicId, long userId, boolean enabled) {
        clinicUser(clinicId, userId).setEnabled(enabled);
    }

    @Transactional(readOnly = true)
    public List<StaffUser> logins(long clinicId) {
        return users.findByClinicIdOrderByUsername(clinicId);
    }

    @Transactional(readOnly = true)
    public long countLogins(long clinicId) {
        return users.countByClinicId(clinicId);
    }

    /**
     * Creates an operator, or gives an existing one a new password (when it was lost).
     *
     * @throws IllegalStateException if the username belongs to a clinic login
     */
    public NewPassword createOrResetOperator(String username) {
        String password = GeneratedPasswords.generate();
        StaffUser existing = users.findByUsernameIgnoreCase(username.strip()).orElse(null);
        if (existing == null) {
            String name = requireFree(username);
            users.save(StaffUser.operator(name, passwordEncoder.encode(password), Language.EN));
            return new NewPassword(name, password);
        }
        if (existing.getRole() != StaffRole.OPERATOR) {
            throw new IllegalStateException(existing.getUsername() + " is a clinic login, not an operator");
        }
        existing.changePasswordHash(passwordEncoder.encode(password));
        existing.setEnabled(true);
        return new NewPassword(existing.getUsername(), password);
    }

    public PasswordChange changeOwnPassword(StaffPrincipal staff, String currentPassword, String newPassword) {
        StaffUser user = own(staff);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            return PasswordChange.WRONG_CURRENT_PASSWORD;
        }
        if (!isLongEnough(newPassword)) {
            return PasswordChange.TOO_SHORT;
        }
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        return PasswordChange.CHANGED;
    }

    public void changeLanguage(StaffPrincipal staff, Language language) {
        own(staff).changeLanguage(language);
    }

    private StaffUser own(StaffPrincipal staff) {
        return (staff.clinicId() == null
                ? users.findByIdAndClinicIdIsNull(staff.id())
                : users.findByIdAndClinicId(staff.id(), staff.clinicId()))
                .orElseThrow(() -> new NotFoundException("Staff user", staff.id()));
    }

    private StaffUser doctorUser(long clinicId, long doctorId) {
        return users.findByClinicIdAndDoctorId(clinicId, doctorId)
                .orElseThrow(() -> new NotFoundException("Login of doctor", doctorId));
    }

    private static void requireLongEnough(String password) {
        if (!isLongEnough(password)) {
            throw new IllegalArgumentException("Passwords have at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }

    private StaffUser clinicUser(long clinicId, long userId) {
        return users.findByIdAndClinicId(userId, clinicId).orElseThrow(() -> new NotFoundException("Staff user", userId));
    }

    private String requireFree(String username) {
        String name = username.strip();
        if (!USERNAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Usernames are 3-40 letters, digits, dots, dashes or underscores");
        }
        if (users.findByUsernameIgnoreCase(name).isPresent()) {
            throw new UsernameTakenException(name);
        }
        return name;
    }
}
