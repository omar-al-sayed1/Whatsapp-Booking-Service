package app.clinicreminders.whatsapp;

public enum MessageKind {

    TEMPLATE,
    INTERACTIVE,
    TEXT,
    BUTTON_REPLY,
    /** Inbound images, voice notes, locations and anything else we don't handle. */
    OTHER
}
