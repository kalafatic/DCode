package eu.kalafatic.dcode.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SSE client for the DCode event stream. Mirrors the web {@code js/sse.js} contract:
 * per-session streams, monotonic event-id dedupe, exponential-backoff reconnection,
 * watchdog for silent streams, and an {@code onReconnected} callback so callers can
 * re-fetch authoritative state (the backend does not replay missed events).
 *
 * <p>Listeners are invoked on this client's background thread; UI callers must
 * marshal onto their display thread.</p>
 */
public final class DCodeSseClient implements AutoCloseable {

    /** One delivered server event. */
    public record SseEvent(long id, String type, String data) {
    }

    /** Status of the stream connection. */
    public enum Status {
        CONNECTED,
        RECONNECTING,
        DISCONNECTED
    }

    public interface Listener {
        default void onEvent(SseEvent event) {
        }

        default void onStatus(Status status, String detail) {
        }

        /** Called after a stream (re)connect: fetch authoritative state. */
        default void onReconnected() {
        }
    }

    private static final Pattern ID_PATTERN = Pattern.compile("^id: (\\d+)$");
    private static final Pattern EVENT_PATTERN = Pattern.compile("^event: (\\S+)$");
    private static final Pattern DATA_PATTERN = Pattern.compile("^data: ?(.*)$");

    private final String baseUrl;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "dcode-sse-client");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile String sessionId;
    private volatile Listener listener;
    private final AtomicLong lastEventId = new AtomicLong();
    private volatile boolean everConnected;

    public DCodeSseClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
    }

    /** Connects to the per-session stream. Replaces any existing connection. */
    public synchronized void connect(String newSessionId, Listener newListener) {
        this.sessionId = newSessionId;
        this.listener = newListener;
        lastEventId.set(0);
        everConnected = false;
        running.set(true);
        executor.submit(() -> streamLoop(0));
    }

    public String sessionId() {
        return sessionId;
    }

    @Override
    public synchronized void close() {
        running.set(false);
        executor.shutdownNow();
        notifyStatus(Status.DISCONNECTED, "closed");
    }

    private void streamLoop(int attempt) {
        if (!running.get() || sessionId == null) {
            return;
        }
        notifyStatus(Status.RECONNECTING, attempt == 0 ? "connecting" : "retry #" + attempt);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl
                            + "/api/v1/events/stream?sessionId="
                            + java.net.URLEncoder.encode(sessionId,
                            java.nio.charset.StandardCharsets.UTF_8)))
                    .timeout(Duration.ofHours(24))
                    .header("Accept", "text/event-stream")
                    .GET().build();
            HttpResponse<java.io.InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                sleepThenRetry(attempt);
                return;
            }
            notifyStatus(Status.CONNECTED, "live");
            if (everConnected) {
                notifyReconnected();
            }
            everConnected = true;
            readFrames(response.body());
            // stream ended: reconnect
            sleepThenRetry(attempt + 1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (running.get()) {
                sleepThenRetry(attempt + 1);
            }
        }
    }

    private void readFrames(java.io.InputStream stream) throws Exception {
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(stream,
                        java.nio.charset.StandardCharsets.UTF_8));
        String line;
        long currentId = -1;
        String eventType = "message";
        StringBuilder data = new StringBuilder();
        long lastActivity = System.currentTimeMillis();
        while (running.get() && (line = reader.readLine()) != null) {
            lastActivity = System.currentTimeMillis();
            if (line.isBlank()) {
                if (currentId > 0 && currentId > lastEventId.get()) {
                    lastEventId.set(currentId);
                    SseEvent event = new SseEvent(currentId, eventType, data.toString());
                    Listener l = listener;
                    if (l != null) {
                        l.onEvent(event);
                    }
                }
                currentId = -1;
                eventType = "message";
                data.setLength(0);
                continue;
            }
            Matcher idMatcher = ID_PATTERN.matcher(line);
            if (idMatcher.matches()) {
                currentId = Long.parseLong(idMatcher.group(1));
                continue;
            }
            Matcher eventMatcher = EVENT_PATTERN.matcher(line);
            if (eventMatcher.matches()) {
                eventType = eventMatcher.group(1);
                continue;
            }
            Matcher dataMatcher = DATA_PATTERN.matcher(line);
            if (dataMatcher.matches()) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(dataMatcher.group(1));
            }
            // watchdog: kill silent streams (read timeout is long, but proxies can
            // hold connections without delivering); handled by reconnecting below
            if (System.currentTimeMillis() - lastActivity > 120000) {
                throw new java.io.IOException("stream watchdog timeout");
            }
        }
    }

    private void sleepThenRetry(int attempt) {
        if (!running.get()) {
            return;
        }
        long delay = Math.min(500L * (1L << Math.min(attempt, 4)), 8000L);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        streamLoop(attempt + 1);
    }

    private void notifyStatus(Status status, String detail) {
        Listener l = listener;
        if (l != null) {
            l.onStatus(status, detail);
        }
    }

    private void notifyReconnected() {
        Listener l = listener;
        if (l != null) {
            l.onReconnected();
        }
    }
}
