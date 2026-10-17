package app.clinicreminders.staff;

public enum StaffRole {

    /** Runs the service across all clinics; has no clinic. */
    OPERATOR,
    OWNER,
    SECRETARY,
    /** One doctor of a clinic: sees only that doctor's appointments, patients and hours. */
    DOCTOR
}
