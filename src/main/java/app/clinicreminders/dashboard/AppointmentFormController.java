package app.clinicreminders.dashboard;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

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
import app.clinicreminders.patient.BookedPatient;
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
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Manual entry: book a patient (found by phone and name, or created), and move an appointment. The
 * time is a free slot from the slot engine or, for squeeze-ins, any other time; the database still
 * refuses two active appointments of a doctor starting together.
 *
 * <p>A family may share a phone: a known number with a new name adds a family member, and a number
 * with several patients needs the name to tell them apart.
 */
@Controller
class AppointmentFormController {

    /** A free slot as offered in the form: value "HH:mm" in clinic time, and its label. */
    record SlotOption(String value, String label) {
    }

    /**
     * A patient offered by the picker.
     *
     * @param upcoming their latest appointment is still to come, so {@code when} is a booking, not a past visit
     */
    record PickerRow(String name, String phone, String language, boolean optedOut, String when, boolean upcoming) {
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
                          @RequestParam(required = false) String time,
                          @RequestParam(defaultValue = "") String phone, @RequestParam(defaultValue = "") String name) {
        Ctx ctx = context.of(staff, request);
        List<Doctor> active = activeDoctors(ctx);
        Long doctor = doctorId != null && ctx.sees(doctorId) ? doctorId : active.isEmpty() ? null : active.getFirst().getId();
        // Booking a known patient (from the Patients page): their language, not the clinic default.
        String language = Optional.ofNullable(PhoneNumbers.parseLebanese(phone))
                .map(phoneE164 -> patientService.household(ctx.clinicId(), phoneE164))
                .flatMap(household -> Optional.ofNullable(chosen(household, name))
                        .or(() -> household.stream().findFirst()))
                .map(patient -> patient.getLanguage().code())
                .orElse(ctx.clinic().getDefaultLanguage().code());
        Form form = new Form(null, doctor, date != null ? date : ctx.today(), time == null ? "" : time, "", phone, name,
                language, null);
        return showForm(ctx, form, null, model);
    }

    /** The patient picker: patients booked at least once, latest first, matching what was typed. */
    @GetMapping("/appointments/patients")
    String patientPicker(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model,
                         @RequestParam(defaultValue = "") String q) {
        Ctx ctx = context.of(staff, request);
        model.addAttribute("pickerQuery", q.strip());
        List<BookedPatient> found = ctx.isDoctor()
                ? patientService.searchBookedWithDoctor(ctx.clinicId(), ctx.doctorId(), q)
                : patientService.searchBooked(ctx.clinicId(), q);
        model.addAttribute("picked", found.stream()
                .map(p -> new PickerRow(p.name(), p.phoneE164(), p.language().code(), p.optedOut(),
                        ctx.formats().dayShort(ctx.dateOf(p.lastAppointment())), p.lastAppointment().isAfter(ctx.now())))
                .toList());
        return "dashboard/appointment-form :: patient-results";
    }

    /**
     * As the secretary types a phone number or a name: is this a patient we know, a new one, or a
     * new member of a family on a known number?
     */
    @GetMapping("/appointments/patient-hint")
    String patientHint(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model,
                       @RequestParam(defaultValue = "") String phone, @RequestParam(defaultValue = "") String name) {
        addHint(context.of(staff, request), PhoneNumbers.parseLebanese(phone), name, model);
        return "dashboard/appointment-form :: patient-hint";
    }

    @GetMapping("/appointments/slots")
    String slotOptions(@AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request, Model model,
                       @RequestParam(required = false) Long doctorId, @RequestParam(required = false) Long appointmentId,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Ctx ctx = context.of(staff, request);
        Long doctor = appointmentId != null ? appointment(ctx, appointmentId).getDoctorId() : doctorId;
        if (doctor != null) {
            ctx.requireSees(doctor, "Doctor", doctor);
        }
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
                @RequestParam(defaultValue = "ar") String language,
                @ModelAttribute("returnTo") String returnTo) {
        Ctx ctx = context.of(staff, request);
        Form form = new Form(null, doctorId, date, time, otherTime, phone, name, language, null);
        LocalTime start = startTime(time, otherTime);
        String phoneE164 = PhoneNumbers.parseLebanese(phone);
        if (doctorId == null) {
            return showForm(ctx, form, "error.doctor-required", model);
        }
        ctx.requireSees(doctorId, "Doctor", doctorId);
        if (phoneE164 == null) {
            return showForm(ctx, form, "error.phone", model);
        }
        List<Patient> household = patientService.household(ctx.clinicId(), phoneE164);
        if (name.isBlank() && household.size() != 1) {
            return showForm(ctx, form, household.isEmpty() ? "error.name-required" : "error.name-which", model);
        }
        if (start == null) {
            return showForm(ctx, form, "error.time-required", model);
        }
        try {
            Patient patient = name.isBlank() ? household.getFirst() : patientService.findOrCreate(ctx.clinicId(),
                    phoneE164, name, language.equals("en") ? Language.EN : Language.AR);
            appointmentService.book(ctx.clinicId(), doctorId, patient.getId(), ctx.instant(date, start),
                    AppointmentSource.MANUAL);
        } catch (SlotTakenException e) {
            return showForm(ctx, form, "error.slot-taken", model);
        }
        return afterSave(redirect, "notice.booked", doctorId, date, returnTo);
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
                      @RequestParam(defaultValue = "") String time, @RequestParam(defaultValue = "") String otherTime,
                      @ModelAttribute("returnTo") String returnTo) {
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
        return afterSave(redirect, "notice.moved", appointment.getDoctorId(), date, returnTo);
    }

    /**
     * "week" when the form was opened from Week, so saving goes back there; the form posts it back.
     * Anything else means Today.
     */
    @ModelAttribute("returnTo")
    String returnTo(@RequestParam(defaultValue = "") String returnTo) {
        return returnTo.equals("week") ? "week" : "";
    }

    private static String afterSave(RedirectAttributes redirect, String notice, long doctorId, LocalDate date,
                                    String returnTo) {
        redirect.addFlashAttribute("notice", notice);
        if (returnTo.equals("week")) {
            return WeekController.redirectToWeek(redirect, doctorId, date);
        }
        redirect.addAttribute("date", date);
        return "redirect:/";
    }

    private String showForm(Ctx ctx, Form form, @Nullable String error, Model model) {
        model.addAttribute("form", form);
        model.addAttribute("doctors", form.appointmentId() == null ? activeDoctors(ctx) : List.of());
        model.addAttribute("slots", form.doctorId() == null ? List.of() : slotOptions(ctx, form.doctorId(), form.date()));
        addHint(ctx, PhoneNumbers.parseLebanese(form.phone()), form.name(), model);
        // Not "error": the layout turns that into a toast; the form shows its own next to the fields.
        model.addAttribute("formError", error);
        return "dashboard/appointment-form";
    }

    /**
     * The hint under the phone: {@code hintPatient} the one being booked (by name, or the number's
     * only patient), {@code hintOthers} the number's other patients, offered as a family to pick from.
     */
    private void addHint(Ctx ctx, @Nullable String phoneE164, String name, Model model) {
        List<Patient> household = phoneE164 == null ? List.of() : patientService.household(ctx.clinicId(), phoneE164);
        Patient chosen = chosen(household, name);
        model.addAttribute("hintPhone", phoneE164);
        model.addAttribute("hintName", name.strip());
        model.addAttribute("hintPatient", chosen);
        model.addAttribute("hintOthers", household.stream().filter(patient -> patient != chosen).toList());
        model.addAttribute("hintKnownNumber", !household.isEmpty());
        model.addAttribute("hintOptedOut", household.stream().anyMatch(Patient::isOptedOut));
    }

    /** The number's patient of this name or, with no name, its only patient; null for a new name (or a family, unnamed). */
    private static @Nullable Patient chosen(List<Patient> household, String name) {
        if (name.isBlank()) {
            return household.size() == 1 ? household.getFirst() : null;
        }
        return household.stream().filter(patient -> patient.hasName(name)).findFirst().orElse(null);
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

    /** The doctors one can book for: the active ones, or for a doctor's login only themself. */
    private List<Doctor> activeDoctors(Ctx ctx) {
        return doctors.findByClinicIdOrderByDisplayName(ctx.clinicId()).stream()
                .filter(Doctor::isActive)
                .filter(doctor -> ctx.sees(doctor.getId()))
                .toList();
    }

    private Appointment appointment(Ctx ctx, long id) {
        Appointment appointment = appointments.findByIdAndClinicId(id, ctx.clinicId())
                .orElseThrow(() -> new NotFoundException("Appointment", id));
        ctx.requireSees(appointment.getDoctorId(), "Appointment", id);
        return appointment;
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
