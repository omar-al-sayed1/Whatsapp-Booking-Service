package app.clinicreminders.dashboard;

import java.util.Locale;

import app.clinicreminders.common.Language;
import app.clinicreminders.staff.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.LocaleResolver;

/**
 * The dashboard's language: the one chosen in this session, else the logged-in user's saved
 * language, else English if the browser prefers it, else Arabic.
 */
public class StaffLocaleResolver implements LocaleResolver {

    private static final String SESSION_ATTRIBUTE = StaffLocaleResolver.class.getName() + ".LANGUAGE";

    public static Language language(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SESSION_ATTRIBUTE) instanceof Language chosen) {
            return chosen;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof StaffPrincipal staff) {
            return staff.language();
        }
        String accept = request.getHeader("Accept-Language");
        return accept != null && accept.strip().toLowerCase(Locale.ROOT).startsWith("en") ? Language.EN : Language.AR;
    }

    public static void remember(HttpServletRequest request, Language language) {
        request.getSession().setAttribute(SESSION_ATTRIBUTE, language);
    }

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        return language(request).locale();
    }

    @Override
    public void setLocale(HttpServletRequest request, @Nullable HttpServletResponse response, @Nullable Locale locale) {
        remember(request, locale != null && locale.getLanguage().equals("en") ? Language.EN : Language.AR);
    }
}
