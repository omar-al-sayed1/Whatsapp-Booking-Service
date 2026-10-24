package app.clinicreminders.staff;

import java.io.IOException;

import app.clinicreminders.dashboard.StaffLocaleResolver;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;

/**
 * Form login for the dashboard. Clinic staff get the dashboard: owners and secretaries all of it,
 * doctors their own day (Today, Week, Patients, their hours; the controllers limit what they see to
 * their own doctor). Operators get {@code /operator}; everyone can change their own password. The WhatsApp webhook and the dev simulator stay open: the webhook is
 * authenticated by its signature, and the simulator only exists with the fake client.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    private static final String[] FRONT_DESK = {StaffRole.OWNER.name(), StaffRole.SECRETARY.name()};

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/css/**", "/js/**", "/fonts/**", "/webjars/**", "/favicon.ico", "/error").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/webhooks/whatsapp").permitAll()
                        // A patient's "Add to my calendar" page: the random token in the link is the key.
                        .requestMatchers(HttpMethod.GET, "/c/*", "/c/*/calendar.ics").permitAll()
                        .requestMatchers("/dev/whatsapp", "/dev/whatsapp/**").permitAll()
                        .requestMatchers("/operator", "/operator/**").hasRole(StaffRole.OPERATOR.name())
                        .requestMatchers("/", "/language", "/account/**").authenticated()
                        // The front desk's: clinic settings, the inbox, adding and editing doctors and their
                        // logins, deleting patients. A doctor may change only their own hours and days off.
                        .requestMatchers("/settings", "/settings/**", "/attention", "/attention/**").hasAnyRole(FRONT_DESK)
                        .requestMatchers(HttpMethod.POST, "/doctors", "/doctors/*", "/doctors/*/delete", "/doctors/*/login",
                                "/doctors/*/login/**", "/patients/*/delete").hasAnyRole(FRONT_DESK)
                        .anyRequest().hasAnyRole(StaffRole.OWNER.name(), StaffRole.SECRETARY.name(), StaffRole.DOCTOR.name()))
                .formLogin(form -> form
                        .loginPage("/login")
                        .successHandler(new LanguageRememberingSuccessHandler())
                        .permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                .requestCache(cache -> cache.requestCache(pagesOnlyRequestCache()))
                .csrf(csrf -> csrf.ignoringRequestMatchers("/webhooks/whatsapp", "/dev/whatsapp/**"))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(new HtmxAwareEntryPoint()));
        return http.build();
    }

    /**
     * Only whole pages are remembered as the place to return to after logging in. Otherwise the
     * 30-second refresh of an open Today page, after the session expired, would send the user to a
     * bare htmx fragment once they log in again, and what the browser fetches on its own (icons,
     * Chrome DevTools' {@code /.well-known/appspecific/com.chrome.devtools.json}) to a 404.
     */
    private static RequestCache pagesOnlyRequestCache() {
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.setRequestMatcher(SecurityConfig::isPageRequest);
        return cache;
    }

    /** A page the user opened: the browser asks for HTML, and it isn't htmx or a {@code /.well-known/} probe. */
    static boolean isPageRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return "GET".equals(request.getMethod())
                && !"true".equals(request.getHeader("HX-Request"))
                && accept != null && accept.contains("text/html")
                && !request.getRequestURI().startsWith(request.getContextPath() + "/.well-known/");
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Starts the session in the user's saved language, then continues to the page they asked for. */
    private static final class LanguageRememberingSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

        @Override
        public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                            Authentication authentication) throws IOException, ServletException {
            if (authentication.getPrincipal() instanceof StaffPrincipal staff) {
                StaffLocaleResolver.remember(request, staff.language());
            }
            super.onAuthenticationSuccess(request, response, authentication);
        }
    }

    /**
     * htmx requests (the 30-second refresh) can't follow a redirect to the login page in place, so
     * they get 401 with {@code HX-Redirect}, which makes htmx load the login page as a whole page.
     */
    private static final class HtmxAwareEntryPoint implements AuthenticationEntryPoint {

        private final LoginUrlAuthenticationEntryPoint login = new LoginUrlAuthenticationEntryPoint("/login");

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                             AuthenticationException exception) throws IOException, ServletException {
            if ("true".equals(request.getHeader("HX-Request"))) {
                response.setHeader("HX-Redirect", request.getContextPath() + "/login");
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            login.commence(request, response, exception);
        }
    }
}
