package app.clinicreminders.patient;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface PatientRepository extends Repository<Patient, Long> {

    Optional<Patient> findByIdAndClinicId(Long id, Long clinicId);

    Optional<Patient> findByClinicIdAndPhoneE164(Long clinicId, String phoneE164);

    Optional<Patient> findByClinicIdAndWaUserId(Long clinicId, String waUserId);

    /**
     * Patients whose name contains {@code name} (any case) or whose phone contains {@code digits}.
     * Pass empty {@code digits} to search by name only. At most 50, by name.
     */
    @Query("""
            select p from Patient p
            where p.clinicId = :clinicId
              and (lower(p.name) like lower(concat('%', :name, '%'))
                   or (:digits <> '' and p.phoneE164 like concat('%', :digits, '%')))
            order by p.name, p.id
            limit 50""")
    List<Patient> search(Long clinicId, String name, String digits);

    /**
     * Patients with at least one appointment, matched like {@link #search}, most recently booked first:
     * the patient picker of the booking form. At most 8.
     */
    @Query("""
            select new app.clinicreminders.patient.BookedPatient(p.id, p.name, p.phoneE164, p.language, p.optedOut,
                                                                 max(a.startsAt))
            from Patient p
            join Appointment a on a.patientId = p.id and a.clinicId = p.clinicId
            where p.clinicId = :clinicId
              and (lower(p.name) like lower(concat('%', :name, '%'))
                   or (:digits <> '' and p.phoneE164 like concat('%', :digits, '%')))
            group by p.id, p.name, p.phoneE164, p.language, p.optedOut
            order by max(a.startsAt) desc, p.id
            limit 8""")
    List<BookedPatient> searchBooked(Long clinicId, String name, String digits);

    Patient save(Patient patient);

    void delete(Patient patient);
}
