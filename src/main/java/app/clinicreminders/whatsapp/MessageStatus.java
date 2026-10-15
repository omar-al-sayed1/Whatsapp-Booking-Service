package app.clinicreminders.whatsapp;

/** Delivery status of an outbound message; inbound messages have none. */
public enum MessageStatus {

    SENT,
    DELIVERED,
    READ,
    FAILED
}
