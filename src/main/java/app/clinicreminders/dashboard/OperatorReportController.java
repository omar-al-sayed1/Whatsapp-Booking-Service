package app.clinicreminders.dashboard;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import app.clinicreminders.clinic.Clinic;
import app.clinicreminders.clinic.ClinicSettings;
import app.clinicreminders.common.Language;
import app.clinicreminders.report.AppointmentFigures;
import app.clinicreminders.report.MonthlyReport;
import app.clinicreminders.report.Reports;
import app.clinicreminders.whatsapp.MessageRates;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Cost tracking for the operator (M6): every clinic's month side by side (what the reminders saved,
 * what the messages cost), each clinic's full report and CSV to send them, and the rate card the
 * estimates come from.
 */
@Controller
@RequestMapping("/operator")
class OperatorReportController {

    /** A clinic's month in the overview. */
    record ClinicRow(long id, String name, AppointmentFigures figures, long messages, String cost, long unpriced) {
    }

    private final Reports reports;
    private final ClinicSettings clinics;
    private final MessageRates rates;
    private final MessageSource messages;
    private final Clock clock;

    OperatorReportController(Reports reports, ClinicSettings clinics, MessageRates rates, MessageSource messages,
                             Clock clock) {
        this.reports = reports;
        this.clinics = clinics;
        this.rates = rates;
        this.messages = messages;
        this.clock = clock;
    }

    /** Months are each clinic's own; which month "now" is follows Lebanon. */
    @GetMapping("/report")
    String overview(HttpServletRequest request, Model model, @RequestParam(required = false) String month) {
        Language viewer = StaffLocaleResolver.language(request);
        Instant now = clock.instant();
        Instant firstClinic = clinics.all().stream().map(Clinic::getCreatedAt).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(now);
        YearMonth shown = ReportView.month(month, firstClinic, Clinic.DEFAULT_ZONE, now);
        List<MonthlyReport> all = reports.allClinics(shown, now);
        model.addAttribute("rows", all.stream()
                .map(r -> new ClinicRow(r.clinicId(), r.clinic(), r.total(), r.messagesSent(),
                        ReportView.cost(r.estimatedUsd(), r.messagesSent(), r.messagesUnpriced()),
                        r.messagesUnpriced()))
                .toList());
        model.addAttribute("total", all.stream().map(MonthlyReport::total).reduce(AppointmentFigures.NONE, AppointmentFigures::plus));
        long totalMessages = all.stream().mapToLong(MonthlyReport::messagesSent).sum();
        long totalUnpriced = all.stream().mapToLong(MonthlyReport::messagesUnpriced).sum();
        model.addAttribute("totalMessages", totalMessages);
        model.addAttribute("totalCost", ReportView.cost(all.stream().map(MonthlyReport::estimatedUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add), totalMessages, totalUnpriced));
        model.addAttribute("noRates", rates.all().isEmpty());
        ReportView.monthNav(model, shown, YearMonth.from(firstClinic.atZone(Clinic.DEFAULT_ZONE)),
                YearMonth.from(now.atZone(Clinic.DEFAULT_ZONE)), viewer, messages);
        return "dashboard/operator-report";
    }

    /** One clinic's report, as the clinic sees it, to check or send them. */
    @GetMapping("/clinics/{id}/report")
    String clinicReport(@PathVariable long id, HttpServletRequest request, Model model,
                        @RequestParam(required = false) String month) {
        Language viewer = StaffLocaleResolver.language(request);
        Clinic clinic = clinics.get(id);
        Instant now = clock.instant();
        MonthlyReport report = clinicMonth(clinic, month, now);
        ReportView.addTo(model, report, "/operator/clinics/" + id + "/report", clinic.getCreatedAt(), now, viewer, messages);
        model.addAttribute("busy", ReportView.busyView(reports.busyHours(report), viewer, messages));
        model.addAttribute("active", "operator-report");
        model.addAttribute("operatorView", true);
        return "dashboard/report";
    }

    @GetMapping("/clinics/{id}/report.csv")
    ResponseEntity<String> clinicCsv(@PathVariable long id, HttpServletRequest request,
                                     @RequestParam(required = false) String month) {
        Clinic clinic = clinics.get(id);
        return ReportView.csv(clinicMonth(clinic, month, clock.instant()), StaffLocaleResolver.language(request), messages);
    }

    @GetMapping("/rates")
    String rates(Model model) {
        model.addAttribute("rates", rates.all());
        model.addAttribute("markets", rates.markets());
        model.addAttribute("categories", rates.categories());
        model.addAttribute("today", LocalDate.ofInstant(clock.instant(), Clinic.DEFAULT_ZONE));
        return "dashboard/operator-rates";
    }

    @PostMapping("/rates")
    String addRate(RedirectAttributes redirect, @RequestParam(defaultValue = "") String market,
                   @RequestParam(defaultValue = "") String category, @RequestParam(defaultValue = "") String usdPerMessage,
                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validFrom) {
        try {
            rates.add(market, category, new BigDecimal(usdPerMessage.strip()), validFrom);
            redirect.addFlashAttribute("notice", "notice.saved");
        } catch (IllegalArgumentException e) {
            // NumberFormatException is one too: not a number.
            redirect.addFlashAttribute("error", "error.rate");
        }
        return "redirect:/operator/rates";
    }

    @PostMapping("/rates/{id}/delete")
    String deleteRate(@PathVariable long id, RedirectAttributes redirect) {
        rates.delete(id);
        redirect.addFlashAttribute("notice", "notice.rate-deleted");
        return "redirect:/operator/rates";
    }

    private MonthlyReport clinicMonth(Clinic clinic, String month, Instant now) {
        YearMonth shown = ReportView.month(month, clinic.getCreatedAt(), clinic.getTimezone(), now);
        return reports.monthly(clinic.getId(), shown, null, now);
    }
}
