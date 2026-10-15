package app.clinicreminders.dashboard;

import java.net.URI;
import java.net.URISyntaxException;

import app.clinicreminders.common.Language;
import app.clinicreminders.staff.StaffAccounts;
import app.clinicreminders.staff.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Login page, the language switch and the operators' placeholder. */
@Controller
class AccountController {

    private final StaffAccounts accounts;

    AccountController(StaffAccounts accounts) {
        this.accounts = accounts;
    }

    /**
     * {@code ?lang=} saves the choice and redirects, because the page's language and direction are
     * worked out before this method runs.
     */
    @GetMapping("/login")
    String login(@RequestParam(required = false) String lang, HttpServletRequest request) {
        if (lang != null) {
            StaffLocaleResolver.remember(request, lang.equals("en") ? Language.EN : Language.AR);
            return "redirect:/login";
        }
        return "dashboard/login";
    }

    /** Switches the dashboard language for this session and saves it for the user's next login. */
    @PostMapping("/language")
    String language(@RequestParam String lang, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request) {
        Language language = lang.equals("en") ? Language.EN : Language.AR;
        StaffLocaleResolver.remember(request, language);
        if (staff.clinicId() != null) {
            accounts.changeLanguage(staff, language);
        }
        return "redirect:" + samePagePath(request.getHeader("Referer"));
    }

    /** Only the path and query of the referring page, so the redirect can never leave this site. */
    static String samePagePath(String referer) {
        if (referer == null) {
            return "/";
        }
        try {
            URI uri = new URI(referer);
            String path = uri.getRawPath();
            if (path == null || !path.startsWith("/") || path.startsWith("//")) {
                return "/";
            }
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        } catch (URISyntaxException e) {
            return "/";
        }
    }

    @GetMapping("/operator")
    String operator() {
        return "dashboard/operator";
    }
}
