package app.clinicreminders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import app.clinicreminders.clinic.DemoClinic;
import app.clinicreminders.common.Language;
import app.clinicreminders.staff.StaffAccounts;
import app.clinicreminders.staff.StaffRole;
import app.clinicreminders.staff.StaffUser;
import app.clinicreminders.staff.StaffUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

/** {@code --create-operator} and {@code --seed-demo}, without the exit that follows them. */
class CommandsIT extends IntegrationTest {

    @Autowired
    StaffAccounts accounts;

    @Autowired
    DemoClinic demo;

    @Autowired
    ConfigurableApplicationContext context;

    @Autowired
    StaffUserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    @Test
    void createOperatorPrintsAPasswordOnceAndRunningItAgainReplacesIt() {
        assertThat(run("--create-operator=omar")).isTrue();
        String first = printedPassword();
        out.reset();
        run("--create-operator=Omar");
        String second = printedPassword();

        StaffUser omar = users.findByUsernameIgnoreCase("omar").orElseThrow();
        assertThat(omar.getRole()).isEqualTo(StaffRole.OPERATOR);
        assertThat(omar.getClinicId()).isNull();
        assertThat(passwordEncoder.matches(first, omar.getPasswordHash())).isFalse();
        assertThat(passwordEncoder.matches(second, omar.getPasswordHash())).isTrue();
    }

    @Test
    void aClinicLoginIsNotTurnedIntoAnOperator() {
        users.save(StaffUser.clinicStaff(data.clinic("A").getId(), "rana", "{noop}x", StaffRole.OWNER, Language.AR));

        assertThatIllegalStateException().isThrownBy(() -> run("--create-operator=rana"));
        assertThat(users.findByUsernameIgnoreCase("rana").orElseThrow().getRole()).isEqualTo(StaffRole.OWNER);
    }

    @Test
    void seedDemoPrintsTheDemoLogin() {
        assertThat(run("--seed-demo")).isTrue();

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Created the Dr. Demo clinic", "Login: drdemo");
        assertThat(passwordEncoder.matches(printedPassword(),
                users.findByUsernameIgnoreCase("drdemo").orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void withoutACommandNothingRuns() {
        assertThat(run()).isFalse();
        assertThat(run("--spring.profiles.active=dev")).isFalse();
        assertThat(out.size()).isZero();
    }

    private boolean run(String... args) {
        return new Commands(accounts, demo, context, new PrintStream(out, true, StandardCharsets.UTF_8))
                .execute(new DefaultApplicationArguments(args));
    }

    private String printedPassword() {
        return out.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.startsWith("Password"))
                .map(line -> line.substring(line.lastIndexOf(' ') + 1))
                .reduce((a, b) -> b)
                .orElseThrow();
    }
}
