package eu.kalafatic.dcode.tests.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JDK HTTP server for provider wire tests (no framework dependencies).
 */
public final class MockHttpServer implements AutoCloseable {

    public record Request(String method, String path, String body, Map<String, String> headers) {
    }

    private final HttpServer server;
    private final Map<String, HttpHandler> routes = new HashMap<>();
    private final List<Request> requests = new ArrayList<>();

    private MockHttpServer(HttpServer server) {
        this.server = server;
    }

    public static MockHttpServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        MockHttpServer mock = new MockHttpServer(server);
        server.createContext("/", exchange -> mock.dispatch(exchange));
        server.start();
        return mock;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    public void on(String method, String path, HttpHandler handler) {
        routes.put(method + " " + path, handler);
    }

    public void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, v.isEmpty() ? "" : v.get(0)));
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                body, headers));
        String routeKey = exchange.getRequestMethod() + " "
                + exchange.getRequestURI().getPath();
        HttpHandler handler = routes.get(routeKey);
        if (handler == null) {
            respond(exchange, 404, "{\"error\":\"not found\"}");
            return;
        }
        handler.handle(exchange);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
