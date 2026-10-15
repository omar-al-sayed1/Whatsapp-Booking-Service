package app.clinicreminders.dashboard;

import java.time.Duration;
import java.util.List;

import app.clinicreminders.booking.ConversationRepository;
import app.clinicreminders.booking.Conversations;
import app.clinicreminders.dashboard.ClinicContext.Ctx;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.staff.StaffPrincipal;
import app.clinicreminders.whatsapp.FailedSend;
import app.clinicreminders.whatsapp.MessageLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Needs attention (SPEC section 5.3): conversations flagged for a human, and failed sends. */
@Controller
class AttentionController {

    static final Duration FAILED_SENDS_WINDOW = Duration.ofDays(7);

    record ConversationRow(Long id, String phone, String patientName, String updated) {
    }

    record FailedRow(String failed, String patientName, String patientPhone, String templateName, String appointment,
                     String error) {
    }

    private final ClinicContext context;
    private final ConversationRepository conversations;
    private final Conversations conversationService;
    private final PatientRepository patients;
    private final MessageLogRepository logs;

    AttentionController(ClinicContext context, ConversationRepository conversations, Conversations conversationService,
                        PatientRepository patients, MessageLogRepository logs) {
        this.context = context;
        this.conversations = conversations;
        this.conversationService = conversationService;
        this.patients = patients;
        this.logs = logs;
    }

    @GetMapping("/attention")
    String attention(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model) {
        Ctx ctx = context.of(staff, request);
        List<ConversationRow> waiting = conversations.findByClinicIdAndNeedsHumanTrueOrderByUpdatedAtDesc(ctx.clinicId())
                .stream()
                .map(c -> new ConversationRow(c.getId(), c.getPatientPhone(),
                        patients.findByClinicIdAndPhoneE164(ctx.clinicId(), c.getPatientPhone()).map(Patient::getName).orElse(null),
                        c.getUpdatedAt() == null ? "" : ctx.formats().dateTime(c.getUpdatedAt())))
                .toList();
        List<FailedRow> failed = logs.findFailedSince(ctx.clinicId(), ctx.now().minus(FAILED_SENDS_WINDOW)).stream()
                .map(f -> row(ctx, f))
                .toList();
        model.addAttribute("waiting", waiting);
        model.addAttribute("failed", failed);
        return "dashboard/attention";
    }

    @PostMapping("/attention/conversations/{id}/handled")
    String handled(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                   RedirectAttributes redirect) {
        conversationService.markHandled(context.of(staff, request).clinicId(), id);
        redirect.addFlashAttribute("notice", "notice.saved");
        return "redirect:/attention";
    }

    private static FailedRow row(Ctx ctx, FailedSend failed) {
        String error = failed.errorCode() == null ? failed.errorTitle() : failed.errorTitle() + " (" + failed.errorCode() + ")";
        return new FailedRow(ctx.formats().dateTime(failed.failedAt()), failed.patientName(), failed.patientPhone(),
                failed.templateName(),
                failed.appointmentStartsAt() == null ? null : ctx.formats().dateTime(failed.appointmentStartsAt()),
                error);
    }
}
