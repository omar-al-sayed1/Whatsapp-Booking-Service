package app.clinicreminders.staff;

import java.util.Collection;
import java.util.List;

import app.clinicreminders.common.Language;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The logged-in staff user. Dashboard code takes the clinic from here, never from the request,
 * so a user can only ever see and change their own clinic.
 */
public record StaffPrincipal(
        long id,
        @Nullable Long clinicId,
        String username,
        String passwordHash,
        StaffRole role,
        Language language,
        boolean enabled) implements UserDetails {

    public static StaffPrincipal of(StaffUser user) {
        return new StaffPrincipal(user.getId(), user.getClinicId(), user.getUsername(), user.getPasswordHash(),
                user.getRole(), user.getLanguage(), user.isEnabled());
    }

    /** @throws AccessDeniedException for operators, who have no clinic */
    public long requireClinicId() {
        if (clinicId == null) {
            throw new AccessDeniedException("Operators have no clinic");
        }
        return clinicId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String toString() {
        return "StaffPrincipal[id=" + id + ", clinicId=" + clinicId + ", username=" + username + ", role=" + role + "]";
    }
}
