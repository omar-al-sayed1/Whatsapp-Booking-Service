package app.clinicreminders.staff;

import java.util.Optional;

import app.clinicreminders.common.CrossTenant;
import org.springframework.data.repository.Repository;

public interface StaffUserRepository extends Repository<StaffUser, Long> {

    /** Usernames are unique across clinics, ignoring case (staff_user_username_uq). */
    @CrossTenant("Login: the clinic is only known once the user is found")
    Optional<StaffUser> findByUsernameIgnoreCase(String username);

    Optional<StaffUser> findByIdAndClinicId(Long id, Long clinicId);

    StaffUser save(StaffUser user);
}
