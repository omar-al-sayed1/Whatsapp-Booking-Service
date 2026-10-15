package app.clinicreminders.booking;

/** States of the WhatsApp booking flow (SPEC section 5.2); {@link BookingFlow} moves between them. */
public enum ConversationState {

    MENU,
    CHOOSE_DOCTOR,
    CHOOSE_DAY,
    CHOOSE_SLOT,
    CONFIRM,
    DONE,
    MY_APPOINTMENT,
    INFO
}
