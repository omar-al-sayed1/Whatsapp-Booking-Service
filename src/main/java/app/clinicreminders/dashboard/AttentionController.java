package app.clinicreminders.dashboard;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import app.clinicreminders.booking.ConversationRepository;
import app.clinicreminders.booking.Conversations;
import app.clinicreminders.dashboard.ClinicContext.Ctx;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.staff.StaffPrincipal;
import app.clinicreminders.whatsapp.FailedSend;
import app.clinicreminders.whatsapp.MessageLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Needs attention (SPEC section 5.3): conversations flagged for a human, and failed sends. */
@Controller
class AttentionController {

    static final Duration FAILED_SENDS_WINDOW = Duration.ofDays(7);
    static final int FAILED_PAGE_SIZE = 20;

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

    /** The failed sends load a page at a time as you scroll; later pages come back as rows only (htmx). */
    @GetMapping("/attention")
    String attention(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model,
                     @RequestParam(defaultValue = "1") int failedPage,
                     @RequestHeader(name = "HX-Request", required = false) String htmx) {
        Ctx ctx = context.of(staff, request);
        int page = Math.max(failedPage, 1);
        Instant since = ctx.now().minus(FAILED_SENDS_WINDOW);
        long failedTotal = logs.countFailedSince(ctx.clinicId(), since);
        List<FailedRow> failed = logs.findFailedSince(ctx.clinicId(), since, PageRequest.of(page - 1, FAILED_PAGE_SIZE))
                .stream()
                .map(f -> row(ctx, f))
                .toList();
        model.addAttribute("failed", failed);
        model.addAttribute("failedTotal", failedTotal);
        model.addAttribute("nextFailedPage", (long) page * FAILED_PAGE_SIZE < failedTotal ? page + 1 : null);
        if ("true".equals(htmx) && page > 1) {
            return "dashboard/attention :: failed-rows";
        }
        List<ConversationRow> waiting = conversations.findByClinicIdAndNeedsHumanTrueOrderByUpdatedAtDesc(ctx.clinicId())
                .stream()
                .map(c -> new ConversationRow(c.getId(), c.getPatientPhone(),
                        names(ctx, c.getPatientPhone()),
                        c.getUpdatedAt() == null ? "" : ctx.formats().dateTime(c.getUpdatedAt())))
                .toList();
        model.addAttribute("waiting", waiting);
        return "dashboard/attention";
    }

    @PostMapping("/attention/conversations/{id}/handled")
    String handled(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                   RedirectAttributes redirect) {
        conversationService.markHandled(context.of(staff, request).clinicId(), id);
        redirect.addFlashAttribute("notice", "notice.saved");
        return "redirect:/attention";
    }

    /** Who has this number: one name, or a family's names; null for an unknown number. */
    private @Nullable String names(Ctx ctx, String phone) {
        List<Patient> household = patients.findByClinicIdAndPhoneE164OrderById(ctx.clinicId(), phone);
        return household.isEmpty() ? null : household.stream().map(Patient::getName).collect(Collectors.joining(", "));
    }

    private static FailedRow row(Ctx ctx, FailedSend failed) {
        String error = failed.errorCode() == null ? failed.errorTitle() : failed.errorTitle() + " (" + failed.errorCode() + ")";
        return new FailedRow(ctx.formats().dateTime(failed.failedAt()), failed.patientName(), failed.patientPhone(),
                failed.templateName(),
                failed.appointmentStartsAt() == null ? null : ctx.formats().dateTime(failed.appointmentStartsAt()),
                error);
    }
}
