package eu.kalafatic.dcode.client;

/**
 * Explicit connection state model shared by all DCode clients.
 *
 * <p>The states distinguish the layers of connectivity: TCP/HTTP connectivity, API
 * availability, API compatibility and backend readiness. A successful HTTP connection
 * does not automatically mean that DCode is fully operational.</p>
 */
public enum ConnectionState {

    /** No connection configured or the previous connection was closed. */
    DISCONNECTED,

    /** A connection attempt is in progress. */
    CONNECTING,

    /** HTTP connectivity established; the backend API is being probed. */
    CONNECTED,

    /** The DCode API is reachable; the backend is still starting up. */
    INITIALIZING,

    /** The DCode API is reachable and the backend reports readiness. */
    READY,

    /** Connected, but the backend reports degraded readiness. */
    DEGRADED,

    /** Connection failed, or the endpoint does not provide the DCode API. */
    ERROR;

    public boolean isUsable() {
        return this == CONNECTED || this == INITIALIZING || this == READY
                || this == DEGRADED;
    }

    public boolean isReady() {
        return this == READY;
    }
}
