package app.clinicreminders.whatsapp.dev;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicRepository;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.reminder.ReminderMessages;
import app.clinicreminders.whatsapp.FakeWhatsAppClient;
import app.clinicreminders.whatsapp.FakeWhatsAppClient.SentMessage;
import app.clinicreminders.whatsapp.MessageLog;
import app.clinicreminders.whatsapp.MessageLogRepository;
import app.clinicreminders.whatsapp.OutboundMessage;
import app.clinicreminders.whatsapp.OutboundMessage.ListMessage;
import app.clinicreminders.whatsapp.OutboundMessage.ListRow;
import app.clinicreminders.whatsapp.OutboundMessage.ReplyButton;
import app.clinicreminders.whatsapp.OutboundMessage.ReplyButtonsMessage;
import app.clinicreminders.whatsapp.OutboundMessage.TemplateMessage;
import app.clinicreminders.whatsapp.OutboundMessage.TextMessage;
import app.clinicreminders.whatsapp.WhatsAppException;
import app.clinicreminders.whatsapp.WhatsAppMessenger;
import app.clinicreminders.whatsapp.webhook.WebhookInbox;
import app.clinicreminders.whatsapp.webhook.WebhookInboxProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Dev simulator ({@code /dev/whatsapp}): shows what the fake client "sent" and plays the patient's
 * side through the real webhook inbox. Only exists with the fake client, so never in production.
 * Developer-facing, so its text isn't in the message bundles. Sending runs the messenger in the
 * request thread, so a simulated retryable failure makes the page wait for the retries.
 */
@Controller
@RequestMapping("/dev/whatsapp")
@ConditionalOnProperty(name = "whatsapp.client", havingValue = "fake")
class DevWhatsAppController {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm:ss");

    /** One sent message as the page shows it. */
    record Row(String waMessageId, String sentAt, String clinic, String to, String kind, String content,
               List<String> quickReplies, List<ReplyButton> buttons, List<ListRow> listRows, String status) {
    }

    private final FakeWhatsAppClient fake;
    private final WhatsAppMessenger messenger;
    private final WebhookInbox inbox;
    private final WebhookInboxProcessor processor;
    private final ClinicRepository clinics;
    private final MessageLogRepository logs;
    private final Clock clock;

    DevWhatsAppController(FakeWhatsAppClient fake, WhatsAppMessenger messenger, WebhookInbox inbox,
                          WebhookInboxProcessor processor, ClinicRepository clinics, MessageLogRepository logs,
                          Clock clock) {
        this.fake = fake;
        this.messenger = messenger;
        this.inbox = inbox;
        this.processor = processor;
        this.clinics = clinics;
        this.logs = logs;
        this.clock = clock;
    }

    @GetMapping
    String page(Model model) {
        List<Clinic> connected = clinics.findByWaPhoneNumberIdNotNullOrderByName();
        Map<String, Clinic> byPhoneNumberId = connected.stream()
                .collect(Collectors.toMap(Clinic::getWaPhoneNumberId, Function.identity()));
        List<Row> rows = fake.recent().stream().map(sent -> row(sent, byPhoneNumberId.get(sent.phoneNumberId()))).toList();
        model.addAttribute("clinics", connected);
        model.addAttribute("rows", rows);
        return "dev/whatsapp";
    }

    @PostMapping("/send")
    String send(@RequestParam long clinicId, @RequestParam String to, @RequestParam String text) {
        messenger.send(clinicId, new TextMessage(to, text), null);
        return "redirect:/dev/whatsapp";
    }

    @PostMapping("/send-buttons")
    String sendButtons(@RequestParam long clinicId, @RequestParam String to, @RequestParam String text,
                       @RequestParam List<String> titles) {
        List<ReplyButton> buttons = titles.stream().filter(t -> !t.isBlank())
                .map(t -> new ReplyButton("dev-" + t.strip().toLowerCase(java.util.Locale.ROOT).replace(' ', '-'), t.strip()))
                .toList();
        messenger.send(clinicId, new ReplyButtonsMessage(to, text, buttons), null);
        return "redirect:/dev/whatsapp";
    }

    @PostMapping("/reply")
    String reply(@RequestParam long clinicId, @RequestParam String from, @RequestParam String text,
                 @RequestParam(required = false) String profileName) {
        Clinic clinic = clinics.findById(clinicId).orElseThrow(() -> new NotFoundException("Clinic", clinicId));
        deliver(SimulatedWebhooks.text(clinic.getWaPhoneNumberId(), from, profileName,
                SimulatedWebhooks.newMessageId(), text, clock.instant()));
        return "redirect:/dev/whatsapp";
    }

    /** Taps a template quick reply ({@code label}), a reply button ({@code buttonId}) or a list row ({@code rowId}). */
    @PostMapping("/tap")
    String tap(@RequestParam String waMessageId, @RequestParam(required = false) String label,
               @RequestParam(required = false) String buttonId, @RequestParam(required = false) String buttonTitle,
               @RequestParam(required = false) String rowId, @RequestParam(required = false) String rowTitle) {
        SentMessage sent = fake.find(waMessageId).orElseThrow(() -> new NotFoundException("Message", waMessageId));
        String id = SimulatedWebhooks.newMessageId();
        String to = sent.message().to();
        String phoneNumberId = sent.phoneNumberId();
        Instant now = clock.instant();
        if (rowId != null) {
            deliver(SimulatedWebhooks.listReply(phoneNumberId, to, null, id, waMessageId, rowId, rowTitle, now));
        } else if (buttonId != null) {
            deliver(SimulatedWebhooks.buttonReply(phoneNumberId, to, null, id, waMessageId, buttonId, buttonTitle, now));
        } else {
            deliver(SimulatedWebhooks.quickReply(phoneNumberId, to, null, id, waMessageId, label, now));
        }
        return "redirect:/dev/whatsapp";
    }

    @PostMapping("/status")
    String status(@RequestParam String waMessageId, @RequestParam String status) {
        SentMessage sent = fake.find(waMessageId).orElseThrow(() -> new NotFoundException("Message", waMessageId));
        boolean failed = status.equals("failed");
        String category = sent.message() instanceof TemplateMessage ? "utility" : "service";
        deliver(SimulatedWebhooks.status(sent.phoneNumberId(), waMessageId, sent.message().to(), status,
                failed ? null : category, null,
                failed ? 131026 : null, failed ? "Message undeliverable" : null, clock.instant()));
        return "redirect:/dev/whatsapp";
    }

    @PostMapping("/fail-next")
    String failNext(@RequestParam String kind) {
        if (kind.equals("retryable")) {
            WhatsAppException rateLimited = WhatsAppException.fromMetaError(130429, "Rate limit hit");
            fake.failNext(rateLimited, rateLimited, rateLimited);
        } else {
            fake.failNext(WhatsAppException.fromMetaError(131026, "Message undeliverable"));
        }
        return "redirect:/dev/whatsapp";
    }

    private void deliver(String payload) {
        inbox.store(payload);
        processor.drain();
    }

    private Row row(SentMessage sent, Clinic clinic) {
        ZoneId zone = clinic == null ? Clinic.DEFAULT_ZONE : clinic.getTimezone();
        String status = clinic == null ? "?" : logs.findByClinicIdAndWaMessageId(clinic.getId(), sent.waMessageId())
                .map(MessageLog::getStatus).map(Enum::name).orElse("?");
        OutboundMessage message = sent.message();
        String kind;
        String content;
        List<String> quickReplies = List.of();
        List<ReplyButton> buttons = List.of();
        List<ListRow> listRows = List.of();
        switch (message) {
            case TemplateMessage template -> {
                kind = "template " + template.name() + " [" + template.languageCode() + "]";
                content = String.join(" | ", template.bodyParameters());
                // The templates' quick replies (SPEC section 6).
                boolean arabic = template.languageCode().startsWith("ar");
                quickReplies = template.name().equals(ReminderMessages.DOCTOR_AWAY_TEMPLATE)
                        ? List.of(arabic ? "احجز موعدًا آخر" : "Book another time")
                        : arabic ? List.of("تأكيد", "إلغاء") : List.of("Confirm", "Cancel");
            }
            case TextMessage text -> {
                kind = "text";
                content = text.body();
            }
            case ReplyButtonsMessage interactive -> {
                kind = "buttons";
                content = interactive.body();
                buttons = interactive.buttons();
            }
            case ListMessage list -> {
                kind = "list [" + list.button() + "]";
                content = list.body();
                listRows = list.rows();
            }
        }
        return new Row(sent.waMessageId(), TIME.format(sent.sentAt().atZone(zone)),
                clinic == null ? sent.phoneNumberId() : clinic.getName(), message.to(), kind, content,
                quickReplies, buttons, listRows, status);
    }
}
