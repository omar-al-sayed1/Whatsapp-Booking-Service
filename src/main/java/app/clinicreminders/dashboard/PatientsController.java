package app.clinicreminders.dashboard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.dashboard.ClinicContext.Ctx;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientService;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.staff.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Patients: search by name or phone, see who has an appointment coming up, and delete on request
 * (SPEC section 5.3). A doctor's login lists their own patients (those with an appointment with
 * them) and only their own appointments; deleting is the front desk's. The list loads a page at a
 * time as you scroll: later pages come back as rows only (htmx), to append. Patients who share a
 * number (a family) say so, except to a doctor, who may not see the others.
 */
@Controller
class PatientsController {

    static final int PAGE_SIZE = 30;

    /**
     * A patient as listed.
     *
     * @param next       their next booked or confirmed appointment ("Wed 21 Oct, 10:20 AM"), if any
     * @param nextDoctor that appointment's doctor
     * @param family     the others on their number ("Karim Haddad, Lina Haddad"), if any
     */
    record PatientRow(Long id, String name, String phoneE164, String language, boolean optedOut,
                      @Nullable String next, @Nullable String nextDoctor, @Nullable Long nextId,
                      @Nullable String family) {
    }

    private final ClinicContext context;
    private final PatientService patients;
    private final AppointmentRepository appointments;
    private final DoctorRepository doctors;

    PatientsController(ClinicContext context, PatientService patients, AppointmentRepository appointments,
                       DoctorRepository doctors) {
        this.context = context;
        this.patients = patients;
        this.appointments = appointments;
        this.doctors = doctors;
    }

    @GetMapping("/patients")
    String search(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model,
                  @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "1") int page,
                  @RequestHeader(name = "HX-Request", required = false) String htmx) {
        Ctx ctx = context.of(staff, request);
        int current = Math.max(page, 1);
        PatientService.Found result = patients.search(ctx.clinicId(), ctx.doctorId(), q, current - 1, PAGE_SIZE);
        List<Patient> found = result.patients();
        Map<Long, Appointment> nextByPatient = new HashMap<>();
        if (!found.isEmpty()) {
            for (Appointment a : appointments.findUpcomingOf(ctx.clinicId(), found.stream().map(Patient::getId).toList(),
                    ctx.now())) {
                if (ctx.sees(a.getDoctorId())) {
                    nextByPatient.putIfAbsent(a.getPatientId(), a);
                }
            }
        }
        Map<String, List<Patient>> households = ctx.isDoctor() ? Map.of()
                : patients.households(ctx.clinicId(), found.stream().map(Patient::getPhoneE164).distinct().toList());
        Map<Long, String> doctorNames = new HashMap<>();
        for (Doctor doctor : doctors.findByClinicIdOrderByDisplayName(ctx.clinicId())) {
            doctorNames.put(doctor.getId(), doctor.getDisplayName());
        }
        model.addAttribute("q", q);
        model.addAttribute("patients", found.stream().map(p -> {
            Appointment next = nextByPatient.get(p.getId());
            return new PatientRow(p.getId(), p.getName(), p.getPhoneE164(), p.getLanguage().code(), p.isOptedOut(),
                    next == null ? null : ctx.formats().dayShort(ctx.dateOf(next.getStartsAt())) + ", "
                            + ctx.formats().time(next.getStartsAt()),
                    next == null ? null : doctorNames.get(next.getDoctorId()),
                    next == null ? null : next.getId(),
                    family(households.getOrDefault(p.getPhoneE164(), List.of()), p));
        }).toList());
        model.addAttribute("total", result.total());
        model.addAttribute("nextPage", (long) current * PAGE_SIZE < result.total() ? current + 1 : null);
        return "true".equals(htmx) && current > 1 ? "dashboard/patients :: rows" : "dashboard/patients";
    }

    /** The others on the patient's number, by name; null when they have it to themselves. */
    private static @Nullable String family(List<Patient> household, Patient patient) {
        String others = household.stream()
                .filter(other -> !other.getId().equals(patient.getId()))
                .map(Patient::getName)
                .collect(Collectors.joining(", "));
        return others.isEmpty() ? null : others;
    }

    @PostMapping("/patients/{id}/delete")
    String delete(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                  RedirectAttributes redirect) {
        patients.delete(context.of(staff, request).clinicId(), id);
        redirect.addFlashAttribute("notice", "notice.patient-deleted");
        return "redirect:/patients";
    }
}
