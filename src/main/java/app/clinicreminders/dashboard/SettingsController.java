package app.clinicreminders.dashboard;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicSettings;
import app.clinicreminders.common.Language;
import app.clinicreminders.staff.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Settings (SPEC section 5.3): the clinic's reminder switches, the patients' default language, and
 * the address and hours patients get from "Info". Doctors have their own page ({@link DoctorsController}).
 */
@Controller
class SettingsController {

    private final ClinicContext context;
    private final ClinicSettings clinicSettings;

    SettingsController(ClinicContext context, ClinicSettings clinicSettings) {
        this.context = context;
        this.clinicSettings = clinicSettings;
    }

    /** @param tab "doctors" from the time doctors were a tab here: those links go to the Doctors page */
    @GetMapping("/settings")
    String settings(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model,
                    @RequestParam(defaultValue = "") String tab) {
        if (tab.equals("doctors")) {
            return "redirect:/doctors";
        }
        Clinic clinic = context.of(staff, request).clinic();
        model.addAttribute("defaultLanguage", clinic.getDefaultLanguage().code());
        model.addAttribute("h24Enabled", clinic.isReminderH24Enabled());
        model.addAttribute("h2Enabled", clinic.isReminderH2Enabled());
        model.addAttribute("address", clinic.getAddress());
        model.addAttribute("hoursText", clinic.getHoursText());
        model.addAttribute("contactMax", Clinic.CONTACT_TEXT_MAX);
        return "dashboard/settings";
    }

    @PostMapping("/settings/clinic")
    String saveClinic(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, RedirectAttributes redirect,
                      @RequestParam String defaultLanguage,
                      @RequestParam(defaultValue = "false") boolean h24Enabled,
                      @RequestParam(defaultValue = "false") boolean h2Enabled,
                      @RequestParam(defaultValue = "") String address,
                      @RequestParam(defaultValue = "") String hoursText) {
        try {
            clinicSettings.update(context.of(staff, request).clinicId(), Language.fromCode(defaultLanguage), h24Enabled,
                    h2Enabled, address, hoursText);
            redirect.addFlashAttribute("notice", "notice.saved");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "error.contact");
        }
        return "redirect:/settings";
    }
}
