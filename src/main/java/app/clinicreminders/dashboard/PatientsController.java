package app.clinicreminders.dashboard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Patients: search by name or phone, see who has an appointment coming up, and delete on request
 * (SPEC section 5.3). A doctor's login lists their own patients (those with an appointment with
 * them) and only their own appointments; deleting is the front desk's.
 */
@Controller
class PatientsController {

    /**
     * A patient as listed.
     *
     * @param next       their next booked or confirmed appointment ("Wed 21 Oct, 10:20 AM"), if any
     * @param nextDoctor that appointment's doctor
     */
    record PatientRow(Long id, String name, String phoneE164, String language, boolean optedOut,
                      @Nullable String next, @Nullable String nextDoctor, @Nullable Long nextId) {
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
                  @RequestParam(defaultValue = "") String q) {
        Ctx ctx = context.of(staff, request);
        List<Patient> found = ctx.isDoctor()
                ? patients.searchOfDoctor(ctx.clinicId(), ctx.doctorId(), q)
                : patients.search(ctx.clinicId(), q);
        Map<Long, Appointment> nextByPatient = new HashMap<>();
        if (!found.isEmpty()) {
            for (Appointment a : appointments.findUpcomingOf(ctx.clinicId(), found.stream().map(Patient::getId).toList(),
                    ctx.now())) {
                if (ctx.sees(a.getDoctorId())) {
                    nextByPatient.putIfAbsent(a.getPatientId(), a);
                }
            }
        }
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
                    next == null ? null : next.getId());
        }).toList());
        return "dashboard/patients";
    }

    @PostMapping("/patients/{id}/delete")
    String delete(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                  RedirectAttributes redirect) {
        patients.delete(context.of(staff, request).clinicId(), id);
        redirect.addFlashAttribute("notice", "notice.patient-deleted");
        return "redirect:/patients";
    }
}
