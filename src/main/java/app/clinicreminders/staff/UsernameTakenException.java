package app.clinicreminders.staff;

/** Usernames are unique across all clinics, ignoring case. */
public class UsernameTakenException extends RuntimeException {

    public UsernameTakenException(String username) {
        super("Username already taken: " + username);
    }
}
