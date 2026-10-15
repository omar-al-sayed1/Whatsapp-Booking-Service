package app.clinicreminders.reminder;

import java.text.Normalizer;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentEvents.CancelledBy;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentService;
import app.clinicreminders.appointment.AppointmentStatus;
import app.clinicreminders.whatsapp.MessageKind;
import app.clinicreminders.whatsapp.MessageLog;
import app.clinicreminders.whatsapp.MessageLogRepository;
import app.clinicreminders.whatsapp.webhook.InboundContent.QuickReply;
import app.clinicreminders.whatsapp.webhook.InboundMessageReceived;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Confirm and Cancel taps on reminder templates (SPEC section 5.1.5). A tap carries the button
 * label and {@code context.id}, the id of the reminder it was on; the message log links that id
 * to the appointment. Runs in the webhook inbox's transaction, so a failure retries the delivery.
 */
@Component
class ReminderReplies {

    private static final Logger log = LoggerFactory.getLogger(ReminderReplies.class);
    private static final Set<String> REMINDER_TEMPLATES = Set.of(ReminderMessages.H24_TEMPLATE, ReminderMessages.H2_TEMPLATE);

    private enum Action { CONFIRM, CANCEL }

    private final MessageLogRepository logs;
    private final AppointmentRepository appointments;
    private final AppointmentService appointmentService;
    private final Set<String> confirmLabels;
    private final Set<String> cancelLabels;
    private final Clock clock;

    ReminderReplies(MessageLogRepository logs, AppointmentRepository appointments, AppointmentService appointmentService,
                    ReminderProperties properties, Clock clock) {
        this.logs = logs;
        this.appointments = appointments;
        this.appointmentService = appointmentService;
        this.confirmLabels = normalized(properties.confirmLabels());
        this.cancelLabels = normalized(properties.cancelLabels());
        this.clock = clock;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void onInbound(InboundMessageReceived event) {
        if (!(event.message().content() instanceof QuickReply reply) || event.message().contextMessageId() == null) {
            return;
        }
        long clinicId = event.clinicId();
        Optional<Long> appointmentId = logs.findByClinicIdAndWaMessageId(clinicId, event.message().contextMessageId())
                .filter(sent -> sent.getKind() == MessageKind.TEMPLATE && REMINDER_TEMPLATES.contains(sent.getTemplateName()))
                .map(MessageLog::getAppointmentId);
        if (appointmentId.isEmpty()) {
            return;
        }
        Action action = actionFor(reply);
        if (action == null) {
            log.info("Clinic {}: reminder reply with an unknown label, ignored", clinicId);
            return;
        }
        Appointment appointment = appointments.findByIdAndClinicId(appointmentId.get(), clinicId).orElse(null);
        if (appointment == null || !isOpen(appointment)) {
            log.info("Clinic {}: {} tap on appointment {} that is no longer open, ignored", clinicId, action, appointmentId.get());
            return;
        }
        switch (action) {
            case CONFIRM -> appointmentService.confirm(clinicId, appointment.getId());
            case CANCEL -> appointmentService.cancel(clinicId, appointment.getId(), CancelledBy.PATIENT);
        }
    }

    /** Booked or confirmed, and not started yet. */
    private boolean isOpen(Appointment appointment) {
        boolean status = appointment.getStatus() == AppointmentStatus.BOOKED
                || appointment.getStatus() == AppointmentStatus.CONFIRMED;
        return status && appointment.getStartsAt().isAfter(clock.instant());
    }

    private Action actionFor(QuickReply reply) {
        for (String label : List.of(reply.text(), reply.payload())) {
            String key = normalize(label);
            if (confirmLabels.contains(key)) {
                return Action.CONFIRM;
            }
            if (cancelLabels.contains(key)) {
                return Action.CANCEL;
            }
        }
        return null;
    }

    private static Set<String> normalized(List<String> labels) {
        return labels.stream().filter(Objects::nonNull).map(ReminderReplies::normalize).collect(Collectors.toUnmodifiableSet());
    }

    private static String normalize(String label) {
        return Normalizer.normalize(label, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }
}
