package eu.kalafatic.dcode.core.server;

/**
 * Single authoritative control surface for the DCode web/API server, shared by
 * the RCP Web Interface menu and the headless CLI. Implemented by the server
 * bundle; consumers never touch the HTTP server implementation directly.
 */
public interface WebServerControl {

    /** Current lifecycle state (never null). */
    WebServerState state();

    /** True when the server is running and bound. */
    boolean isRunning();

    /**
     * Starts the server. Idempotent. Fails with {@link IllegalStateException}
     * when {@code webServer.enabled=false}.
     */
    void start();

    /** Stops the server, releasing its resources and port. Idempotent. */
    void stop();

    /** Clean stop/start sequence. */
    void restart();

    /** Bound port, or -1 when not running. */
    int port();

    /** Base URL (http://bindAddress:port/), or null when not running. */
    String url();
}
