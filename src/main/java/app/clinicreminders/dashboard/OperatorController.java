package app.clinicreminders.dashboard;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicSettings;
import app.clinicreminders.common.Language;
import app.clinicreminders.common.PhoneNumbers;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.reminder.ReminderKind;
import app.clinicreminders.reminder.ReminderMessages;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.staff.StaffAccounts;
import app.clinicreminders.staff.StaffAccounts.NewPassword;
import app.clinicreminders.staff.StaffRole;
import app.clinicreminders.staff.UsernameTakenException;
import app.clinicreminders.whatsapp.OutboundMessage.TemplateMessage;
import app.clinicreminders.whatsapp.WhatsAppConnections;
import app.clinicreminders.whatsapp.WhatsAppConnections.ConnectionException;
import app.clinicreminders.whatsapp.WhatsAppException;
import app.clinicreminders.whatsapp.WhatsAppMessenger;
import app.clinicreminders.whatsapp.WhatsAppMessenger.SendOutcome;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Operator onboarding (SPEC section 5.5): clinics, their WhatsApp connection, a test message and
 * their logins. Only operators reach {@code /operator/**} (see {@code SecurityConfig}); here the
 * clinic comes from the path, because the operator runs every clinic.
 */
@Controller
@RequestMapping("/operator")
class OperatorController {

    /** Meta's sample template on the test accounts it creates ("Get started" guide). */
    static final String HELLO_WORLD = "hello_world";
    static final String HELLO_WORLD_LANGUAGE = "en_US";

    record ClinicRow(long id, String name, String timezone, String language, boolean connected, long logins) {
    }

    /** The result of a test message, shown once on the clinic page. */
    record TestOutcome(String result, String detail) {
    }

    private final ClinicSettings clinics;
    private final WhatsAppConnections connections;
    private final StaffAccounts accounts;
    private final WhatsAppMessenger messenger;
    private final ReminderMessages reminderMessages;
    private final DoctorRepository doctors;
    private final MessageSource messages;
    private final Clock clock;

    OperatorController(ClinicSettings clinics, WhatsAppConnections connections, StaffAccounts accounts,
                       WhatsAppMessenger messenger, ReminderMessages reminderMessages, DoctorRepository doctors,
                       MessageSource messages, Clock clock) {
        this.clinics = clinics;
        this.connections = connections;
        this.accounts = accounts;
        this.messenger = messenger;
        this.reminderMessages = reminderMessages;
        this.doctors = doctors;
        this.messages = messages;
        this.clock = clock;
    }

    @GetMapping
    String clinics(Model model) {
        List<ClinicRow> rows = clinics.all().stream()
                .map(c -> new ClinicRow(c.getId(), c.getName(), c.getTimezone().getId(), c.getDefaultLanguage().code(),
                        c.getWaPhoneNumberId() != null && c.getWaAccessTokenEncrypted() != null,
                        accounts.countLogins(c.getId())))
                .toList();
        model.addAttribute("clinics", rows);
        model.addAttribute("defaultZone", Clinic.DEFAULT_ZONE.getId());
        return "dashboard/operator";
    }

    @PostMapping("/clinics")
    String create(RedirectAttributes redirect, @RequestParam(defaultValue = "") String name,
                  @RequestParam(defaultValue = "") String timezone, @RequestParam String defaultLanguage) {
        ZoneId zone = zone(timezone);
        if (zone == null) {
            redirect.addFlashAttribute("error", "error.timezone");
            return "redirect:/operator";
        }
        try {
            Clinic clinic = clinics.create(name, zone, Language.fromCode(defaultLanguage));
            redirect.addFlashAttribute("notice", "notice.saved");
            return "redirect:/operator/clinics/" + clinic.getId();
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "error.clinic-name");
            return "redirect:/operator";
        }
    }

    @GetMapping("/clinics/{id}")
    String clinic(@PathVariable long id, Model model) {
        Clinic clinic = clinics.get(id);
        model.addAttribute("clinic", clinic);
        model.addAttribute("connection", connections.connection(id));
        model.addAttribute("logins", accounts.logins(id));
        model.addAttribute("roles", List.of(StaffRole.OWNER, StaffRole.SECRETARY));
        model.addAttribute("webhookUrl", ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/webhooks/whatsapp").toUriString());
        return "dashboard/operator-clinic";
    }

    @PostMapping("/clinics/{id}")
    String updateDetails(@PathVariable long id, RedirectAttributes redirect, @RequestParam(defaultValue = "") String name,
                         @RequestParam(defaultValue = "") String timezone, @RequestParam String defaultLanguage) {
        ZoneId zone = zone(timezone);
        if (zone == null) {
            redirect.addFlashAttribute("error", "error.timezone");
            return redirectTo(id);
        }
        try {
            clinics.updateDetails(id, name, zone, Language.fromCode(defaultLanguage));
            redirect.addFlashAttribute("notice", "notice.saved");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "error.clinic-name");
        }
        return redirectTo(id);
    }

    @PostMapping("/clinics/{id}/whatsapp")
    String connect(@PathVariable long id, RedirectAttributes redirect,
                   @RequestParam(defaultValue = "") String phoneNumberId,
                   @RequestParam(defaultValue = "") String businessAccountId,
                   @RequestParam(defaultValue = "") String accessToken,
                   @RequestParam(defaultValue = "false") boolean removeToken) {
        try {
            connections.connect(id, phoneNumberId, businessAccountId, accessToken, removeToken);
            redirect.addFlashAttribute("notice", "notice.saved");
        } catch (ConnectionException e) {
            redirect.addFlashAttribute("error", errorKey(e));
        }
        return redirectTo(id);
    }

    @PostMapping("/clinics/{id}/subscribe")
    String subscribe(@PathVariable long id, RedirectAttributes redirect) {
        clinics.get(id);
        try {
            connections.subscribeToWebhooks(id);
            redirect.addFlashAttribute("notice", "notice.subscribed");
        } catch (ConnectionException e) {
            redirect.addFlashAttribute("error", errorKey(e));
        } catch (WhatsAppException e) {
            redirect.addFlashAttribute("metaError", e.getMessage());
        }
        return redirectTo(id);
    }

    /**
     * Sends through the messenger, so it is logged and costed like any message. Retries can make
     * the request wait a few seconds; acceptable for a button the operator presses once.
     */
    @PostMapping("/clinics/{id}/test")
    String test(@PathVariable long id, RedirectAttributes redirect, @RequestParam(defaultValue = "") String to,
                @RequestParam String kind) {
        Clinic clinic = clinics.get(id);
        String phone = PhoneNumbers.parseLebanese(to);
        if (phone == null) {
            redirect.addFlashAttribute("error", "error.phone");
            return redirectTo(id);
        }
        TemplateMessage message = kind.equals("reminder") ? sampleReminder(clinic, phone)
                : new TemplateMessage(phone, HELLO_WORLD, HELLO_WORLD_LANGUAGE, List.of());
        SendOutcome outcome = messenger.send(id, message, null);
        redirect.addFlashAttribute("testOutcome", switch (outcome.result()) {
            case SENT -> new TestOutcome("sent", outcome.waMessageId());
            case FAILED -> new TestOutcome("failed", outcome.errorCode() == null ? outcome.errorTitle()
                    : "(#" + outcome.errorCode() + ") " + outcome.errorTitle());
            case OPTED_OUT -> new TestOutcome("opted-out", "");
        });
        return redirectTo(id);
    }

    @PostMapping("/clinics/{id}/logins")
    String addLogin(@PathVariable long id, RedirectAttributes redirect, @RequestParam(defaultValue = "") String username,
                    @RequestParam StaffRole role, @RequestParam String language) {
        clinics.get(id);
        if (role == StaffRole.OPERATOR) {
            redirect.addFlashAttribute("error", "error.username");
            return redirectTo(id);
        }
        try {
            redirect.addFlashAttribute("newPassword", accounts.createClinicLogin(id, username, role, Language.fromCode(language)));
        } catch (UsernameTakenException e) {
            redirect.addFlashAttribute("error", "error.username-taken");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "error.username");
        }
        return redirectTo(id);
    }

    @PostMapping("/clinics/{id}/logins/{userId}/reset")
    String resetPassword(@PathVariable long id, @PathVariable long userId, RedirectAttributes redirect) {
        NewPassword password = accounts.resetPassword(id, userId);
        redirect.addFlashAttribute("newPassword", password);
        return redirectTo(id);
    }

    @PostMapping("/clinics/{id}/logins/{userId}/enabled")
    String setEnabled(@PathVariable long id, @PathVariable long userId, RedirectAttributes redirect,
                      @RequestParam boolean enabled) {
        accounts.setEnabled(id, userId, enabled);
        redirect.addFlashAttribute("notice", "notice.saved");
        return redirectTo(id);
    }

    /**
     * The clinic's own 24-hour reminder template with sample values, in the clinic's language:
     * proves the template that matters is approved for this account.
     */
    private TemplateMessage sampleReminder(Clinic clinic, String phone) {
        Language language = clinic.getDefaultLanguage();
        String doctorName = doctors.findByClinicIdOrderByDisplayName(clinic.getId()).stream()
                .filter(Doctor::isActive).map(Doctor::getDisplayName).findFirst()
                .orElseGet(() -> messages.getMessage("operator.test.sample-doctor", null, language.locale()));
        Patient patient = new Patient(clinic.getId(), messages.getMessage("operator.test.sample-patient", null,
                language.locale()), phone, language);
        Doctor doctor = new Doctor(clinic.getId(), doctorName, 30);
        LocalDate tomorrow = LocalDate.ofInstant(clock.instant(), clinic.getTimezone()).plusDays(1);
        return reminderMessages.reminder(ReminderKind.H24, patient, doctor,
                tomorrow.atTime(LocalTime.of(10, 0)).atZone(clinic.getTimezone()).toInstant(), clinic.getTimezone());
    }

    private static String errorKey(ConnectionException e) {
        return switch (e.problem()) {
            case PHONE_NUMBER_ID_TAKEN -> "error.phone-number-id-taken";
            case TOO_LONG -> "error.whatsapp-too-long";
            case NO_ENCRYPTION_KEY -> "error.no-encryption-key";
            case NOT_CONNECTED -> "error.not-connected";
        };
    }

    private static ZoneId zone(String timezone) {
        try {
            return ZoneId.of(timezone.strip());
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static String redirectTo(long clinicId) {
        return "redirect:/operator/clinics/" + clinicId;
    }
}
