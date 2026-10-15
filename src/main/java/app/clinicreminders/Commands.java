package app.clinicreminders;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import app.clinicreminders.clinic.DemoClinic;
import app.clinicreminders.clinic.DemoClinic.Seeded;
import app.clinicreminders.staff.StaffAccounts;
import app.clinicreminders.staff.StaffAccounts.NewPassword;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * One-off commands, run against the configured database and then exit:
 * <ul>
 *   <li>{@code --create-operator=<username>}: creates an operator login, or gives an existing one a
 *       new password, and prints the password once (never logged).</li>
 *   <li>{@code --seed-demo}: creates or refreshes the "Dr. Demo" clinic ({@link DemoClinic}) and
 *       prints its login.</li>
 * </ul>
 * {@link #commandLine} keeps a command from clashing with a running instance: no fixed port, no
 * schedulers.
 */
@Component
class Commands implements ApplicationRunner {

    static final String CREATE_OPERATOR = "create-operator";
    static final String SEED_DEMO = "seed-demo";

    private final StaffAccounts accounts;
    private final DemoClinic demo;
    private final ConfigurableApplicationContext context;
    private final PrintStream out;

    @Autowired
    Commands(StaffAccounts accounts, DemoClinic demo, ConfigurableApplicationContext context) {
        this(accounts, demo, context, System.out);
    }

    Commands(StaffAccounts accounts, DemoClinic demo, ConfigurableApplicationContext context, PrintStream out) {
        this.accounts = accounts;
        this.demo = demo;
        this.context = context;
        this.out = out;
    }

    /** With a command, adds settings so it runs beside a running instance; otherwise returns the arguments as they are. */
    static String[] commandLine(String[] args) {
        boolean command = Arrays.stream(args)
                .anyMatch(arg -> arg.startsWith("--" + CREATE_OPERATOR + "=") || arg.equals("--" + SEED_DEMO));
        if (!command) {
            return args;
        }
        return Stream.concat(Arrays.stream(args), Stream.of(
                "--server.port=0",
                "--reminders.scheduler.enabled=false",
                "--whatsapp.inbox.async=false")).toArray(String[]::new);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (execute(args)) {
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }

    /** @return whether a command ran */
    boolean execute(ApplicationArguments args) {
        boolean ran = false;
        List<String> operators = args.getOptionValues(CREATE_OPERATOR);
        if (operators != null) {
            for (String username : operators) {
                NewPassword login = accounts.createOrResetOperator(username);
                out.println();
                out.println("Operator login: " + login.username());
                out.println("Password (shown once, not logged): " + login.password());
                out.println();
            }
            ran = true;
        }
        if (args.containsOption(SEED_DEMO)) {
            Seeded seeded = demo.seed();
            out.println();
            out.println((seeded.created() ? "Created" : "Refreshed") + " the Dr. Demo clinic (id " + seeded.clinicId()
                    + ") with " + seeded.appointments() + " appointments around today.");
            out.println("Login: " + seeded.login().username());
            out.println("Password (shown once, not logged): " + seeded.login().password());
            out.println("Play the patients at /dev/whatsapp (dev profile), e.g. from +96170990001.");
            out.println();
            ran = true;
        }
        return ran;
    }
}
