package app.clinicreminders.dashboard;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentEvents.CancelledBy;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentStatus;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.dashboard.ClinicContext.Ctx;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
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
import org.springframework.web.bind.annotation.RequestParam;

/**
 * One appointment's details, opened from Week (in the slide-over) with Move and Cancel. The
 * actions are the existing ones; {@code returnTo=week} brings the secretary back to Week after them.
 */
@Controller
class AppointmentController {

    /** What the details panel shows. */
    record Details(long id, @Nullable String patientName, @Nullable String phone, @Nullable String language,
                   String doctor, String date, String hours, String status, boolean cancelledByPatient, String source,
                   boolean movable) {
    }

    private final ClinicContext context;
    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final DoctorRepository doctors;

    AppointmentController(ClinicContext context, AppointmentRepository appointments, PatientRepository patients,
                          DoctorRepository doctors) {
        this.context = context;
        this.appointments = appointments;
        this.patients = patients;
        this.doctors = doctors;
    }

    @GetMapping("/appointments/{id}")
    String details(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                   Model model, @RequestParam(defaultValue = "") String returnTo) {
        Ctx ctx = context.of(staff, request);
        Appointment appointment = appointments.findByIdAndClinicId(id, ctx.clinicId())
                .orElseThrow(() -> new NotFoundException("Appointment", id));
        Patient patient = appointment.getPatientId() == null ? null
                : patients.findByIdAndClinicId(appointment.getPatientId(), ctx.clinicId()).orElse(null);
        String doctor = doctors.findByIdAndClinicId(appointment.getDoctorId(), ctx.clinicId())
                .map(Doctor::getDisplayName).orElse("");
        model.addAttribute("details", new Details(
                appointment.getId(),
                patient == null ? null : patient.getName(),
                patient == null ? null : patient.getPhoneE164(),
                patient == null ? null : patient.getLanguage().code(),
                doctor,
                ctx.formats().date(ctx.dateOf(appointment.getStartsAt())),
                ctx.formats().time(appointment.getStartsAt()) + " – " + ctx.formats().time(appointment.getEndsAt()),
                appointment.getStatus().name(),
                appointment.getStatus() == AppointmentStatus.CANCELLED && appointment.getCancelledBy() == CancelledBy.PATIENT,
                appointment.getSource().name(),
                WeekController.isMovable(appointment, ctx.now())));
        model.addAttribute("returnTo", "week".equals(returnTo) ? "week" : "");
        model.addAttribute("date", ctx.dateOf(appointment.getStartsAt()));
        model.addAttribute("doctorId", appointment.getDoctorId());
        return "dashboard/appointment";
    }
}
