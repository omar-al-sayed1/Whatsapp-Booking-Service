package app.clinicreminders.whatsapp;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev and test stand-in for the Cloud API: keeps the last {@value #CAPACITY} messages in memory
 * (they back the {@code /dev/whatsapp} page) and logs them. Nothing leaves the machine.
 */
public class FakeWhatsAppClient implements WhatsAppClient {

    private static final Logger log = LoggerFactory.getLogger(FakeWhatsAppClient.class);
    private static final int CAPACITY = 200;

    public record SentMessage(Instant sentAt, String phoneNumberId, OutboundMessage message, String waMessageId) {
    }

    private final Deque<SentMessage> sent = new ConcurrentLinkedDeque<>();
    private final Queue<WhatsAppException> failures = new ConcurrentLinkedQueue<>();
    private final Clock clock;

    public FakeWhatsAppClient(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String send(WhatsAppAccount account, OutboundMessage message) {
        WhatsAppException failure = failures.poll();
        if (failure != null) {
            log.info("Fake WhatsApp: failing send to {} with {}", message.to(), failure.getMessage());
            throw failure;
        }
        String id = "wamid.FAKE." + UUID.randomUUID();
        sent.addFirst(new SentMessage(clock.instant(), account.phoneNumberId(), message, id));
        while (sent.size() > CAPACITY) {
            sent.pollLast();
        }
        log.info("Fake WhatsApp: {} -> {}", id, message);
        return id;
    }

    /** Newest first. */
    public List<SentMessage> recent() {
        return List.copyOf(sent);
    }

    public Optional<SentMessage> find(String waMessageId) {
        return sent.stream().filter(m -> m.waMessageId().equals(waMessageId)).findFirst();
    }

    /** The next sends throw these, in order, before sending normally again. */
    public void failNext(WhatsAppException... failures) {
        this.failures.addAll(Arrays.asList(failures));
    }

    public void reset() {
        sent.clear();
        failures.clear();
    }
}
