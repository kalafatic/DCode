package eu.kalafatic.dcode.server;

import eu.kalafatic.dcode.core.event.DCodeEvent;
import eu.kalafatic.dcode.core.event.EventBus;
import eu.kalafatic.dcode.core.log.DCodeLog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Server-Sent Events hub: bridges the DCode event bus to HTTP streaming clients.
 *
 * <p>Each connection gets a bounded queue; slow clients drop events rather than block
 * the event bus. The hub is deliberately the only live-event channel of the server.</p>
 */
public final class SseHub {

    private static final int QUEUE_CAPACITY = 256;
    private static final int MAX_CONNECTIONS = 64;

    private final EventBus eventBus;
    private final EventBus.Subscription subscription;
    private final Map<BlockingQueue<String>, String> connections = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong eventSequence =
            new java.util.concurrent.atomic.AtomicLong();

    public SseHub(EventBus eventBus) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.subscription = eventBus.subscribe(e -> true, this::broadcast);
    }

    /** Opens an SSE stream for one session (all events when sessionId is null). */
    public InputStream open(String sessionId) {
        if (connections.size() >= MAX_CONNECTIONS) {
            return null;
        }
        BlockingQueue<String> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        connections.put(queue, sessionId);
        return new SseInputStream(queue, sessionId, connections);
    }

    private void broadcast(DCodeEvent event) {
        // Monotonic ids across connections: clients can dedupe on reconnect overlap.
        long eventId = eventSequence.incrementAndGet();
        for (Map.Entry<BlockingQueue<String>, String> entry : connections.entrySet()) {
            String sessionId = entry.getValue();
            if (sessionId != null && !sessionId.equals(event.sessionId())) {
                continue;
            }
            String frame = "id: " + eventId + "\n"
                    + "event: " + event.type().name() + "\n"
                    + "data: " + event.dataJson().replace("\n", "\\n") + "\n\n";
            if (!entry.getKey().offer(frame)) {
                entry.getKey().poll(); // drop oldest; slow client
            }
        }
    }

    public void close() {
        subscription.unsubscribe();
        for (BlockingQueue<String> queue : connections.keySet()) {
            queue.offer("event: shutdown\ndata: server shutting down\n\n");
        }
    }

    /** InputStream that reads SSE frames from a queue; terminates when the queue is done. */
    private static final class SseInputStream extends InputStream {

        private final BlockingQueue<String> queue;
        private final Map<BlockingQueue<String>, String> connections;
        private byte[] buffer = new byte[0];
        private int position;
        private boolean closed;

        SseInputStream(BlockingQueue<String> queue, String sessionId,
                       Map<BlockingQueue<String>, String> connections) {
            this.queue = queue;
            this.connections = connections;
            this.queue.offer("event: connected\ndata: {\"sessionId\":\""
                    + (sessionId == null ? "" : sessionId) + "\"}\n\n");
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (closed) {
                return -1;
            }
            while (position >= buffer.length) {
                String frame;
                try {
                    frame = queue.poll(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
                if (frame == null) {
                    // keep-alive comment so proxies do not drop the connection
                    frame = ": keep-alive\n\n";
                }
                buffer = frame.getBytes(StandardCharsets.UTF_8);
                position = 0;
            }
            int available = buffer.length - position;
            int toCopy = Math.min(available, length);
            System.arraycopy(buffer, position, bytes, offset, toCopy);
            position += toCopy;
            return toCopy;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            connections.remove(queue);
        }
    }
}
