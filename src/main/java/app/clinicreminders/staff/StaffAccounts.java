package app.clinicreminders.staff;

import app.clinicreminders.common.Language;
import app.clinicreminders.common.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class StaffAccounts {

    private final StaffUserRepository users;

    StaffAccounts(StaffUserRepository users) {
        this.users = users;
    }

    public void changeLanguage(StaffPrincipal staff, Language language) {
        users.findByIdAndClinicId(staff.id(), staff.requireClinicId())
                .orElseThrow(() -> new NotFoundException("Staff user", staff.id()))
                .changeLanguage(language);
    }
}
