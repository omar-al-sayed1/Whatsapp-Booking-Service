package app.clinicreminders.appointment;

/** Every status except CANCELLED occupies the doctor's time. */
public enum AppointmentStatus {

    BOOKED,
    CONFIRMED,
    CANCELLED,
    NO_SHOW,
    ARRIVED
}
