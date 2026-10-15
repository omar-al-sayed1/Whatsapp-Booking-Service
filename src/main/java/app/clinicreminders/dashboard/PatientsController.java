package app.clinicreminders.dashboard;

import app.clinicreminders.dashboard.ClinicContext.Ctx;
import app.clinicreminders.patient.PatientService;
import app.clinicreminders.staff.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Patients: search by name or phone, and delete on request (SPEC section 5.3). */
@Controller
class PatientsController {

    private final ClinicContext context;
    private final PatientService patients;

    PatientsController(ClinicContext context, PatientService patients) {
        this.context = context;
        this.patients = patients;
    }

    @GetMapping("/patients")
    String search(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model,
                  @RequestParam(defaultValue = "") String q) {
        Ctx ctx = context.of(staff, request);
        model.addAttribute("q", q);
        model.addAttribute("patients", patients.search(ctx.clinicId(), q));
        return "dashboard/patients";
    }

    @PostMapping("/patients/{id}/delete")
    String delete(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                  RedirectAttributes redirect) {
        patients.delete(context.of(staff, request).clinicId(), id);
        redirect.addFlashAttribute("notice", "notice.patient-deleted");
        return "redirect:/patients";
    }
}
