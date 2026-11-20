package eu.kalafatic.dcode.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.config.provider.ChatRequest;
import eu.kalafatic.dcode.config.provider.ProviderException;
import eu.kalafatic.dcode.config.runtime.RuntimeRegistry;
import eu.kalafatic.dcode.config.runtime.StreamHandle;
import eu.kalafatic.dcode.config.runtime.StreamListener;
import eu.kalafatic.dcode.config.runtime.StreamResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Direct inference streaming hub: bridges {@link RuntimeRegistry} streams to HTTP SSE
 * clients of the direct chat (no coding task involved). Every generation gets a request
 * id used for cancellation; the hub is deliberately separate from the session event
 * stream.
 */
public final class InferenceStreamHub {

    private static final int QUEUE_CAPACITY = 1024;
    private static final int MAX_ACTIVE = 32;

    private final RuntimeRegistry runtimes;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, StreamHandle> active = new ConcurrentHashMap<>();

    public InferenceStreamHub(RuntimeRegistry runtimes) {
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    }

    public record StartedStream(String requestId, String runtimeId, String model,
                                InputStream stream) {
    }

    /** Starts a streamed generation; the caller owns the returned stream. */
    public StartedStream start(String runtimeId, ChatRequest request) {
        if (active.size() >= MAX_ACTIVE) {
            throw new IllegalStateException("Too many active inference streams");
        }
        String requestId = UUID.randomUUID().toString();
        BlockingQueue<String> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        queue.offer("event: start\ndata: {\"requestId\":\"" + requestId
                + "\",\"runtimeId\":\"" + escape(runtimeId) + "\",\"model\":\""
                + escape(request.model()) + "\"}\n\n");
        StreamHandle handle = runtimes.stream(runtimeId, request,
                new StreamListener() {
                    @Override
                    public void onToken(String text) {
                        ObjectNode node = mapper.createObjectNode();
                        node.put("token", text);
                        queue.offer("data: " + compact(node) + "\n\n");
                    }

                    @Override
                    public void onComplete(StreamResult result) {
                        ObjectNode node = mapper.createObjectNode();
                        node.put("content", result.response().content());
                        node.put("finishReason", result.response().finishReason());
                        node.put("promptTokens", result.response().promptTokens());
                        node.put("completionTokens", result.response().completionTokens());
                        node.put("cancelled", result.cancelled());
                        queue.offer("event: done\ndata: " + compact(node) + "\n\n");
                        active.remove(requestId);
                    }

                    @Override
                    public void onError(ProviderException error) {
                        ObjectNode node = mapper.createObjectNode();
                        node.put("message", error.getMessage());
                        queue.offer("event: error\ndata: " + compact(node) + "\n\n");
                        active.remove(requestId);
                    }
                });
        active.put(requestId, handle);
        return new StartedStream(requestId, runtimeId, request.model(),
                new SseQueueInputStream(queue, requestId, active));
    }

    /** Cancels an active generation. Returns false for unknown request ids. */
    public boolean cancel(String requestId) {
        StreamHandle handle = active.remove(requestId);
        if (handle == null) {
            return false;
        }
        handle.cancel();
        return true;
    }

    public int activeCount() {
        return active.size();
    }

    public void close() {
        for (String requestId : active.keySet()) {
            cancel(requestId);
        }
    }

    private static String compact(JsonNode node) {
        return node.toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** InputStream over a frame queue; closes when the generation finishes. */
    private static final class SseQueueInputStream extends InputStream {

        private final BlockingQueue<String> queue;
        private final Map<String, StreamHandle> active;
        private final String requestId;
        private byte[] buffer = new byte[0];
        private int position;
        private boolean closed;
        private boolean finished;

        SseQueueInputStream(BlockingQueue<String> queue, String requestId,
                            Map<String, StreamHandle> active) {
            this.queue = queue;
            this.requestId = requestId;
            this.active = active;
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
                if (finished) {
                    return -1;
                }
                String frame;
                try {
                    frame = queue.poll(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
                if (frame == null) {
                    frame = ": keep-alive\n\n";
                }
                if (frame.startsWith("event: done")
                        || frame.startsWith("event: error")) {
                    finished = true;
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
            StreamHandle handle = active.remove(requestId);
            if (handle != null) {
                handle.cancel();
            }
        }
    }
}
