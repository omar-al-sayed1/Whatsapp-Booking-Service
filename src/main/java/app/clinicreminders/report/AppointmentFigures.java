package app.clinicreminders.report;

import org.jspecify.annotations.Nullable;

/**
 * What became of the appointments starting in a month: one doctor's, or the whole clinic's.
 *
 * @param appointments      all of them, cancelled ones too
 * @param bookedOnWhatsApp  booked by the patient on WhatsApp
 * @param confirmed         confirmed by the patient (a reminder's Confirm), whatever happened next
 * @param remindersSent     reminders sent for them
 * @param arrived           the patient came
 * @param noShows           the patient did not come
 * @param slotsSaved        cancelled by the patient on WhatsApp at least 2 hours ahead: time the clinic could give to
 *                          someone else instead of a no-show (SPEC section 5.4)
 * @param refilled          of the slots saved, those someone else booked again
 * @param lateCancellations cancelled by the patient less than 2 hours ahead
 * @param cancelledByClinic cancelled by the clinic: the front desk, or the doctor being away
 * @param upcoming          booked or confirmed, still to come
 * @param unmarked          booked or confirmed and already started: nobody marked them arrived or no-show
 */
public record AppointmentFigures(long appointments, long bookedOnWhatsApp, long confirmed, long remindersSent,
                                 long arrived, long noShows, long slotsSaved, long refilled, long lateCancellations,
                                 long cancelledByClinic, long upcoming, long unmarked) {

    public static final AppointmentFigures NONE = new AppointmentFigures(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public AppointmentFigures plus(AppointmentFigures other) {
        return new AppointmentFigures(appointments + other.appointments, bookedOnWhatsApp + other.bookedOnWhatsApp,
                confirmed + other.confirmed, remindersSent + other.remindersSent, arrived + other.arrived,
                noShows + other.noShows, slotsSaved + other.slotsSaved, refilled + other.refilled,
                lateCancellations + other.lateCancellations, cancelledByClinic + other.cancelledByClinic,
                upcoming + other.upcoming, unmarked + other.unmarked);
    }

    AppointmentFigures withRemindersSent(long sent) {
        return new AppointmentFigures(appointments, bookedOnWhatsApp, confirmed, sent, arrived, noShows, slotsSaved,
                refilled, lateCancellations, cancelledByClinic, upcoming, unmarked);
    }

    /** No-shows out of the appointments that took place or should have (arrived or no-show), rounded; null if none. */
    public @Nullable Integer noShowPercent() {
        long due = arrived + noShows;
        return due == 0 ? null : (int) Math.round(100.0 * noShows / due);
    }
}
