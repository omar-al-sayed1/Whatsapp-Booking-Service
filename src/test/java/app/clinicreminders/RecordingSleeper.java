package app.clinicreminders;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import app.clinicreminders.common.Sleeper;

/** Records retry waits instead of waiting. */
public final class RecordingSleeper implements Sleeper {

    private final List<Duration> sleeps = new CopyOnWriteArrayList<>();

    @Override
    public void sleep(Duration duration) {
        sleeps.add(duration);
    }

    public List<Duration> sleeps() {
        return List.copyOf(sleeps);
    }

    public void reset() {
        sleeps.clear();
    }
}
