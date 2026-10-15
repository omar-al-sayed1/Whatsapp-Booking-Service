package app.clinicreminders;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CommandsTest {

    @Test
    void aCommandRunsOnAFreePortWithoutSchedulers() {
        assertThat(Commands.commandLine(new String[] {"--seed-demo"})).containsExactly(
                "--seed-demo", "--server.port=0", "--reminders.scheduler.enabled=false", "--whatsapp.inbox.async=false");
        assertThat(Commands.commandLine(new String[] {"--spring.profiles.active=dev", "--create-operator=omar"}))
                .startsWith("--spring.profiles.active=dev", "--create-operator=omar").contains("--server.port=0");
    }

    @Test
    void aNormalStartIsLeftAlone() {
        String[] args = {"--spring.profiles.active=dev", "--create-operator"};

        assertThat(Commands.commandLine(args)).isSameAs(args);
        assertThat(Commands.commandLine(new String[0])).isEmpty();
    }
}
