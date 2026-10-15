package app.clinicreminders.clinic;

import java.util.List;
import java.util.Optional;

import app.clinicreminders.common.CrossTenant;
import org.springframework.data.repository.Repository;

/** The clinic is the tenant itself, so its id is the tenant filter. */
public interface ClinicRepository extends Repository<Clinic, Long> {

    Optional<Clinic> findById(Long id);

    @CrossTenant("Webhook routing: a delivery identifies the clinic only by its WhatsApp phone number id")
    Optional<Clinic> findByWaPhoneNumberId(String waPhoneNumberId);

    @CrossTenant("Dev simulator page: lists every clinic that can receive simulated messages")
    List<Clinic> findByWaPhoneNumberIdNotNullOrderByName();

    Clinic save(Clinic clinic);
}
