package app.clinicreminders.staff;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
class StaffUserDetailsService implements UserDetailsService {

    private final StaffUserRepository users;

    StaffUserDetailsService(StaffUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return users.findByUsernameIgnoreCase(username.strip())
                .map(StaffPrincipal::of)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }
}
