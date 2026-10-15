package app.clinicreminders.common;

import java.time.Duration;

/** Waits between retries. Tests swap in one that doesn't wait. */
@FunctionalInterface
public interface Sleeper {

    Sleeper THREAD = duration -> {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry", e);
        }
    };

    void sleep(Duration duration);
}
