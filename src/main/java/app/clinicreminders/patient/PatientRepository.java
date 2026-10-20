package app.clinicreminders.patient;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface PatientRepository extends Repository<Patient, Long> {

    Optional<Patient> findByIdAndClinicId(Long id, Long clinicId);

    /** Everyone on this number (a parent and their children), first added first. */
    List<Patient> findByClinicIdAndPhoneE164OrderById(Long clinicId, String phoneE164);

    /** Everyone on the number with this WhatsApp user id, first added first. */
    List<Patient> findByClinicIdAndWaUserIdOrderById(Long clinicId, String waUserId);

    /** Everyone on any of these numbers, by name: who shares a number on the Patients page. */
    List<Patient> findByClinicIdAndPhoneE164InOrderByName(Long clinicId, Collection<String> phones);

    /** Whether this number opted out (all its patients are, so any one tells). */
    boolean existsByClinicIdAndPhoneE164AndOptedOutTrue(Long clinicId, String phoneE164);

    /**
     * Patients whose name contains {@code name} (any case) or whose phone contains {@code digits},
     * by name, one page at a time. Pass empty {@code digits} to search by name only.
     */
    @Query("""
            select p from Patient p
            where p.clinicId = :clinicId
              and (lower(p.name) like lower(concat('%', :name, '%'))
                   or (:digits <> '' and p.phoneE164 like concat('%', :digits, '%')))
            order by p.name, p.id""")
    List<Patient> search(Long clinicId, String name, String digits, Pageable page);

    /** How many {@link #search} finds in all. */
    @Query("""
            select count(p) from Patient p
            where p.clinicId = :clinicId
              and (lower(p.name) like lower(concat('%', :name, '%'))
                   or (:digits <> '' and p.phoneE164 like concat('%', :digits, '%')))""")
    long countSearch(Long clinicId, String name, String digits);

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

    /** As {@link #search}, among one doctor's patients: those with at least one appointment with them. */
    @Query("""
            select p from Patient p
            where p.clinicId = :clinicId
              and exists (select 1 from Appointment a
                          where a.patientId = p.id and a.clinicId = p.clinicId and a.doctorId = :doctorId)
              and (lower(p.name) like lower(concat('%', :name, '%'))
                   or (:digits <> '' and p.phoneE164 like concat('%', :digits, '%')))
            order by p.name, p.id""")
    List<Patient> searchOfDoctor(Long clinicId, Long doctorId, String name, String digits, Pageable page);

    /** How many {@link #searchOfDoctor} finds in all. */
    @Query("""
            select count(p) from Patient p
            where p.clinicId = :clinicId
              and exists (select 1 from Appointment a
                          where a.patientId = p.id and a.clinicId = p.clinicId and a.doctorId = :doctorId)
              and (lower(p.name) like lower(concat('%', :name, '%'))
                   or (:digits <> '' and p.phoneE164 like concat('%', :digits, '%')))""")
    long countSearchOfDoctor(Long clinicId, Long doctorId, String name, String digits);

    /** As {@link #searchBooked}, counting only appointments with that doctor: a doctor's own picker. */
    @Query("""
            select new app.clinicreminders.patient.BookedPatient(p.id, p.name, p.phoneE164, p.language, p.optedOut,
                                                                 max(a.startsAt))
            from Patient p
            join Appointment a on a.patientId = p.id and a.clinicId = p.clinicId
            where p.clinicId = :clinicId
              and a.doctorId = :doctorId
              and (lower(p.name) like lower(concat('%', :name, '%'))
                   or (:digits <> '' and p.phoneE164 like concat('%', :digits, '%')))
            group by p.id, p.name, p.phoneE164, p.language, p.optedOut
            order by max(a.startsAt) desc, p.id
            limit 8""")
    List<BookedPatient> searchBookedWithDoctor(Long clinicId, Long doctorId, String name, String digits);

    Patient save(Patient patient);

    void delete(Patient patient);
}
