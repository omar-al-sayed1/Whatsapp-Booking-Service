package app.clinicreminders.staff;

import java.util.List;
import java.util.Optional;

import app.clinicreminders.common.CrossTenant;
import org.springframework.data.repository.Repository;

public interface StaffUserRepository extends Repository<StaffUser, Long> {

    /** Usernames are unique across clinics, ignoring case (staff_user_username_uq). */
    @CrossTenant("Login: the clinic is only known once the user is found")
    Optional<StaffUser> findByUsernameIgnoreCase(String username);

    Optional<StaffUser> findByIdAndClinicId(Long id, Long clinicId);

    /** An operator's own account (operators belong to no clinic). */
    @CrossTenant("Operators have no clinic; only ever called with the logged-in operator's own id")
    Optional<StaffUser> findByIdAndClinicIdIsNull(Long id);

    List<StaffUser> findByClinicIdOrderByUsername(Long clinicId);

    /** A doctor's login, if they have one. */
    Optional<StaffUser> findByClinicIdAndDoctorId(Long clinicId, Long doctorId);

    long countByClinicId(Long clinicId);

    StaffUser save(StaffUser user);

    void delete(StaffUser user);
}
