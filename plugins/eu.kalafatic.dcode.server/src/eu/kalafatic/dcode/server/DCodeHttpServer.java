package eu.kalafatic.dcode.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * DCode HTTP API server (NanoHTTPD, family convention).
 *
 * <p>REST endpoints under {@code /api/v1}, SSE event streams under
 * {@code /api/v1/events/stream}. Handlers contain no business logic: every operation
 * goes through the application services. Error responses use a top-level envelope
 * consistent with the family TEIP error model.</p>
 */
public final class DCodeHttpServer extends NanoHTTPD {

    public static final String API_PREFIX = "/api/v1";

    private final DCodeApi api;
    private final StaticContent staticContent;

    public DCodeHttpServer(String host, int port, DCodeApi api) {
        this(host, port, api, null);
    }

    public DCodeHttpServer(String host, int port, DCodeApi api, StaticContent staticContent) {
        super(host, port);
        this.api = api;
        this.staticContent = staticContent;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String path = session.getUri();
        String token = session.getHeaders().getOrDefault("authorization", "");
        if (path.startsWith(API_PREFIX) && !api.isAuthorized(token)) {
            return json(Response.Status.UNAUTHORIZED, api.error("AUTHENTICATION_FAILED",
                    "Missing or invalid bearer token", null, false));
        }
        try {
            if (path.equals("/health")) {
                DCodeApi.ApiPayload payload = api.dispatch(session.getMethod().name(), path, session);
                return json(statusOf(payload.status()), payload.body());
            }
            if (path.equals(API_PREFIX + "/events/stream")) {
                return serveEventStream(session);
            }
            if (path.equals(API_PREFIX + "/inference/chat/stream")
                    && session.getMethod().name().equals("POST")) {
                return serveInferenceStream(session);
            }
            if (path.startsWith(API_PREFIX)) {
                DCodeApi.ApiPayload payload = api.dispatch(session.getMethod().name(), path, session);
                return json(statusOf(payload.status()), payload.body());
            }
            return serveStatic(path);
        } catch (Exception e) {
            return json(Response.Status.INTERNAL_ERROR,
                    api.error("INTERNAL_ERROR", String.valueOf(e.getMessage()), null, false));
        }
    }

    private Response serveStatic(String path) {
        if (staticContent == null) {
            return json(Response.Status.NOT_FOUND,
                    api.error("NOT_FOUND", "No web content configured", null, false));
        }
        try {
            String relative = sanitize(path);
            java.io.InputStream stream = staticContent.open(relative);
            if (stream == null) {
                // SPA fallback: unknown paths serve the application shell.
                stream = staticContent.open("index.html");
            }
            if (stream == null) {
                return json(Response.Status.NOT_FOUND,
                        api.error("NOT_FOUND", "Resource not found: " + path, null, false));
            }
            byte[] bytes = stream.readAllBytes();
            stream.close();
            Response response = newFixedLengthResponse(Response.Status.OK,
                    mimeTypeOf(relative), new java.io.ByteArrayInputStream(bytes),
                    bytes.length);
            response.addHeader("Cache-Control", "no-cache");
            return response;
        } catch (Exception e) {
            return json(Response.Status.INTERNAL_ERROR,
                    api.error("INTERNAL_ERROR", String.valueOf(e.getMessage()), null, false));
        }
    }

    private static String sanitize(String path) {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        if (relative.isBlank()) {
            return "index.html";
        }
        while (relative.contains("..")) {
            relative = relative.replace("..", "");
        }
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        return relative.isBlank() ? "index.html" : relative;
    }

    private static String mimeTypeOf(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
            return "application/javascript; charset=utf-8";
        }
        if (lower.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            return "text/plain; charset=utf-8";
        }
        if (lower.endsWith(".woff2")) {
            return "font/woff2";
        }
        return "application/octet-stream";
    }

    private Response serveEventStream(IHTTPSession session) {
        Map<String, String> params = session.getParms();
        String sessionId = params.get("sessionId");
        InputStream stream = api.openEventStream(sessionId);
        if (stream == null) {
            return json(Response.Status.BAD_REQUEST,
                    api.error("VALIDATION_FAILED", "Missing sessionId parameter", null, false));
        }
        Response response = newChunkedResponse(Response.Status.OK, "text/event-stream", stream);
        response.addHeader("Cache-Control", "no-cache");
        response.addHeader("Connection", "keep-alive");
        return response;
    }

    /** Direct inference chat streaming: SSE tokens over the runtime adapter stream. */
    private Response serveInferenceStream(IHTTPSession session) {
        try {
            String body = readBody(session);
            InputStream stream = api.openInferenceStream(body);
            Response response = newChunkedResponse(Response.Status.OK,
                    "text/event-stream; charset=utf-8", stream);
            response.addHeader("Cache-Control", "no-cache");
            response.addHeader("Connection", "keep-alive");
            return response;
        } catch (IllegalArgumentException e) {
            return json(Response.Status.BAD_REQUEST,
                    api.error("VALIDATION_FAILED", e.getMessage(), null, false));
        } catch (IllegalStateException e) {
            return json(Response.Status.SERVICE_UNAVAILABLE,
                    api.error("SERVICE_UNAVAILABLE", e.getMessage(), null, false));
        } catch (Exception e) {
            return json(Response.Status.INTERNAL_ERROR,
                    api.error("INTERNAL_ERROR", String.valueOf(e.getMessage()), null, false));
        }
    }

    static Response json(Response.Status status, String body) {
        Response response = newFixedLengthResponse(status, "application/json", body);
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }

    private static Response.Status statusOf(int code) {
        return switch (code) {
            case 200 -> Response.Status.OK;
            case 400 -> Response.Status.BAD_REQUEST;
            case 401 -> Response.Status.UNAUTHORIZED;
            case 403 -> Response.Status.FORBIDDEN;
            case 404 -> Response.Status.NOT_FOUND;
            case 405 -> Response.Status.METHOD_NOT_ALLOWED;
            case 409 -> Response.Status.CONFLICT;
            case 422 -> Response.Status.BAD_REQUEST;
            case 500 -> Response.Status.INTERNAL_ERROR;
            case 503 -> Response.Status.SERVICE_UNAVAILABLE;
            case 504 -> Response.Status.REQUEST_TIMEOUT;
            default -> Response.Status.INTERNAL_ERROR;
        };
    }

    static String readBody(IHTTPSession session) throws IOException {
        Map<String, String> files = new java.util.HashMap<>();
        try {
            session.parseBody(files);
        } catch (Exception e) {
            throw new IOException("Failed to read request body", e);
        }
        String body = files.get("postData");
        return body != null ? body : "";
    }
}
