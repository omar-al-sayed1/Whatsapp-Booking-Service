package app.clinicreminders.dashboard;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;

import app.clinicreminders.appointment.Appointment;
import app.clinicreminders.appointment.AppointmentRepository;
import app.clinicreminders.appointment.AppointmentService;
import app.clinicreminders.appointment.AppointmentSource;
import app.clinicreminders.appointment.AppointmentStatus;
import app.clinicreminders.appointment.SlotTakenException;
import app.clinicreminders.common.Language;
import app.clinicreminders.common.NotFoundException;
import app.clinicreminders.common.PhoneNumbers;
import app.clinicreminders.dashboard.ClinicContext.Ctx;
import app.clinicreminders.patient.Patient;
import app.clinicreminders.patient.PatientRepository;
import app.clinicreminders.patient.PatientService;
import app.clinicreminders.scheduling.Doctor;
import app.clinicreminders.scheduling.DoctorRepository;
import app.clinicreminders.scheduling.SlotService;
import app.clinicreminders.staff.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Manual entry: book a patient (found by phone, or created), and move an appointment. The time
 * is a free slot from the slot engine or, for squeeze-ins, any other time; the database still
 * refuses two active appointments of a doctor starting together.
 */
@Controller
class AppointmentFormController {

    /** A free slot as offered in the form: value "HH:mm" in clinic time, and its label. */
    record SlotOption(String value, String label) {
    }

    /** What the form shows and posts back. */
    record Form(@Nullable Long appointmentId, @Nullable Long doctorId, LocalDate date, String time, String otherTime,
                String phone, String name, String language, @Nullable String patientLabel) {
    }

    private final ClinicContext context;
    private final DoctorRepository doctors;
    private final SlotService slots;
    private final AppointmentService appointmentService;
    private final AppointmentRepository appointments;
    private final PatientRepository patients;
    private final PatientService patientService;

    AppointmentFormController(ClinicContext context, DoctorRepository doctors, SlotService slots,
                              AppointmentService appointmentService, AppointmentRepository appointments,
                              PatientRepository patients, PatientService patientService) {
        this.context = context;
        this.doctors = doctors;
        this.slots = slots;
        this.appointmentService = appointmentService;
        this.appointments = appointments;
        this.patients = patients;
        this.patientService = patientService;
    }

    @GetMapping("/appointments/new")
    String newAppointment(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model,
                          @RequestParam(required = false) Long doctorId,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                          @RequestParam(required = false) String time) {
        Ctx ctx = context.of(staff, request);
        List<Doctor> active = activeDoctors(ctx);
        Long doctor = doctorId != null ? doctorId : active.isEmpty() ? null : active.getFirst().getId();
        Form form = new Form(null, doctor, date != null ? date : ctx.today(), time == null ? "" : time, "", "", "",
                ctx.clinic().getDefaultLanguage().code(), null);
        return showForm(ctx, form, null, model);
    }

    @GetMapping("/appointments/slots")
    String slotOptions(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model,
                       @RequestParam(required = false) Long doctorId, @RequestParam(required = false) Long appointmentId,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Ctx ctx = context.of(staff, request);
        Long doctor = appointmentId != null ? appointment(ctx, appointmentId).getDoctorId() : doctorId;
        model.addAttribute("slots", doctor == null ? List.of() : slotOptions(ctx, doctor, date));
        model.addAttribute("form", new Form(appointmentId, doctor, date, "", "", "", "", "", null));
        return "dashboard/appointment-form :: slots";
    }

    @PostMapping("/appointments")
    String book(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model,
                RedirectAttributes redirect,
                @RequestParam(required = false) Long doctorId,
                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                @RequestParam(defaultValue = "") String time, @RequestParam(defaultValue = "") String otherTime,
                @RequestParam(defaultValue = "") String phone, @RequestParam(defaultValue = "") String name,
                @RequestParam(defaultValue = "ar") String language) {
        Ctx ctx = context.of(staff, request);
        Form form = new Form(null, doctorId, date, time, otherTime, phone, name, language, null);
        LocalTime start = startTime(time, otherTime);
        String phoneE164 = PhoneNumbers.parseLebanese(phone);
        if (doctorId == null) {
            return showForm(ctx, form, "error.doctor-required", model);
        }
        if (phoneE164 == null) {
            return showForm(ctx, form, "error.phone", model);
        }
        boolean known = patients.findByClinicIdAndPhoneE164(ctx.clinicId(), phoneE164).isPresent();
        if (!known && name.isBlank()) {
            return showForm(ctx, form, "error.name-required", model);
        }
        if (start == null) {
            return showForm(ctx, form, "error.time-required", model);
        }
        try {
            Patient patient = patientService.findOrCreate(ctx.clinicId(), phoneE164, name,
                    language.equals("en") ? Language.EN : Language.AR);
            appointmentService.book(ctx.clinicId(), doctorId, patient.getId(), ctx.instant(date, start),
                    AppointmentSource.MANUAL);
        } catch (SlotTakenException e) {
            return showForm(ctx, form, "error.slot-taken", model);
        }
        redirect.addFlashAttribute("notice", "notice.booked");
        redirect.addAttribute("date", date);
        return "redirect:/";
    }

    @GetMapping("/appointments/{id}/reschedule")
    String rescheduleForm(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff,
                          HttpServletRequest request, Model model,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Ctx ctx = context.of(staff, request);
        Appointment appointment = movable(ctx, id);
        LocalDate day = date != null ? date : ctx.dateOf(appointment.getStartsAt());
        return showForm(ctx, rescheduleForm(ctx, appointment, day, "", ""), null, model);
    }

    @PostMapping("/appointments/{id}/reschedule")
    String reschedule(@PathVariable long id, @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request,
                      Model model, RedirectAttributes redirect,
                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                      @RequestParam(defaultValue = "") String time, @RequestParam(defaultValue = "") String otherTime) {
        Ctx ctx = context.of(staff, request);
        Appointment appointment = movable(ctx, id);
        Form form = rescheduleForm(ctx, appointment, date, time, otherTime);
        LocalTime start = startTime(time, otherTime);
        if (start == null) {
            return showForm(ctx, form, "error.time-required", model);
        }
        try {
            appointmentService.reschedule(ctx.clinicId(), id, ctx.instant(date, start));
        } catch (SlotTakenException e) {
            return showForm(ctx, form, "error.slot-taken", model);
        }
        redirect.addFlashAttribute("notice", "notice.moved");
        redirect.addAttribute("date", date);
        return "redirect:/";
    }

    private String showForm(Ctx ctx, Form form, @Nullable String error, Model model) {
        model.addAttribute("form", form);
        model.addAttribute("doctors", form.appointmentId() == null ? activeDoctors(ctx) : List.of());
        model.addAttribute("slots", form.doctorId() == null ? List.of() : slotOptions(ctx, form.doctorId(), form.date()));
        model.addAttribute("error", error);
        return "dashboard/appointment-form";
    }

    private Form rescheduleForm(Ctx ctx, Appointment appointment, LocalDate date, String time, String otherTime) {
        String patientLabel = appointment.getPatientId() == null ? null
                : patients.findByIdAndClinicId(appointment.getPatientId(), ctx.clinicId())
                        .map(p -> p.getName() + " · " + p.getPhoneE164()).orElse(null);
        String doctorName = doctors.findByIdAndClinicId(appointment.getDoctorId(), ctx.clinicId())
                .map(Doctor::getDisplayName).orElse("");
        String current = ctx.formats().dateTime(appointment.getStartsAt());
        return new Form(appointment.getId(), appointment.getDoctorId(), date, time, otherTime, "", "", "",
                (patientLabel == null ? "" : patientLabel + " · ") + doctorName + " · " + current);
    }

    private List<SlotOption> slotOptions(Ctx ctx, long doctorId, LocalDate date) {
        return slots.freeSlots(ctx.clinicId(), doctorId, date, date).stream()
                .map(slot -> {
                    LocalTime local = ctx.timeOf(slot.start());
                    return new SlotOption(local.toString(), ctx.formats().time(local));
                })
                .toList();
    }

    private List<Doctor> activeDoctors(Ctx ctx) {
        return doctors.findByClinicIdOrderByDisplayName(ctx.clinicId()).stream().filter(Doctor::isActive).toList();
    }

    private Appointment appointment(Ctx ctx, long id) {
        return appointments.findByIdAndClinicId(id, ctx.clinicId()).orElseThrow(() -> new NotFoundException("Appointment", id));
    }

    /** Booked or confirmed and not started yet. */
    private Appointment movable(Ctx ctx, long id) {
        Appointment appointment = appointment(ctx, id);
        boolean open = appointment.getStatus() == AppointmentStatus.BOOKED
                || appointment.getStatus() == AppointmentStatus.CONFIRMED;
        if (!open || !appointment.getStartsAt().isAfter(ctx.now())) {
            throw new IllegalStateException("Appointment " + id + " can no longer be moved");
        }
        return appointment;
    }

    /** "Other time" wins over the picked slot; null if neither is a time. */
    private static @Nullable LocalTime startTime(String slot, String other) {
        String value = other.isBlank() ? slot : other;
        try {
            return value.isBlank() ? null : LocalTime.parse(value.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
