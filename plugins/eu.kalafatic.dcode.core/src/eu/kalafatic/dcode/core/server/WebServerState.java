package eu.kalafatic.dcode.core.server;

/**
 * Lifecycle state of the DCode web/API server. {@link #DISABLED} means
 * {@code webServer.enabled=false}; the server itself reports
 * STOPPED / STARTING / RUNNING / STOPPING / FAILED.
 */
public enum WebServerState {
    DISABLED,
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED
}
