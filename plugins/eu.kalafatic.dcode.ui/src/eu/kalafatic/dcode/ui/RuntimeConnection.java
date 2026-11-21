package eu.kalafatic.dcode.ui;

import eu.kalafatic.dcode.client.BackendIdentity;
import eu.kalafatic.dcode.client.BackendProbe;
import eu.kalafatic.dcode.client.ConnectionState;
import eu.kalafatic.dcode.client.DCodeApiClient;
import eu.kalafatic.dcode.client.DCodeSseClient;
import eu.kalafatic.dcode.core.model.Session;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

/**
 * Runtime connection singleton: one API client + one SSE client for the whole
 * workbench. Local mode targets the embedded server (port published by the server
 * bundle); remote mode targets a user-configured endpoint. The presentation layer
 * never sees the difference.
 *
 * <p>The connection follows an explicit state model
 * ({@code DISCONNECTED/CONNECTING/CONNECTED/INITIALIZING/READY/DEGRADED/ERROR}).
 * On every (re)connect the backend identity is probed through {@code /api/v1/health};
 * endpoints that do not provide the DCode API (wrong service, wrong port) are
 * reported as an ERROR with the detected identity instead of producing a stream of
 * HTTP 404 failures.</p>
 *
 * <p>The remote auth token is stored in the Eclipse secure storage (encrypted at
 * rest by the platform); plain {@code java.util.prefs} serves only as a fallback for
 * deployments without the Equinox security bundle.</p>
 */
public final class RuntimeConnection {

    private static final RuntimeConnection INSTANCE = new RuntimeConnection();
    private static final String PREF_ENDPOINT = "remoteEndpoint";
    private static final String PREF_TOKEN = "authToken";
    private static final String SECURE_NODE = "/eu/kalafatic/dcode/ui";
    private static final String SECURE_TOKEN = "remoteAuthToken";

    /** Listener of connection state changes (invoked on the UI thread). */
    public interface ConnectionStateListener {
        void stateChanged(ConnectionState state, BackendIdentity identity,
                          String diagnostic);
    }

    private final AtomicReference<DCodeApiClient> api = new AtomicReference<>();
    private final AtomicReference<DCodeSseClient> sse = new AtomicReference<>();
    private final AtomicReference<ConnectionState> state =
            new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final List<ConnectionStateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final BackendProbe probe = new BackendProbe();
    private volatile boolean localMode = true;
    private volatile Session activeSession;
    private volatile BackendIdentity identity;
    private volatile String lastDiagnostic = "";

    private RuntimeConnection() {
    }

    public static RuntimeConnection instance() {
        return INSTANCE;
    }

    /** Resolves and initializes the connection (idempotent). */
    public synchronized void initialize() {
        Preferences preferences = Preferences.userNodeForPackage(RuntimeConnection.class);
        String endpoint = resolveEndpoint(preferences);
        String token = readToken(preferences);
        DCodeApiClient client = new DCodeApiClient(endpoint);
        if (token != null && !token.isBlank()) {
            client.setAuthToken(token);
        }
        DCodeSseClient sseClient = new DCodeSseClient(endpoint);
        api.set(client);
        sse.set(sseClient);
        Ui.log("network", "Connecting to " + endpoint + "…");
        Ui.log("info", "Checking API compatibility.");
        probeBackend(client);
    }

    /**
     * Probes the backend identity and transitions the connection state. Used both on
     * startup and for the user-triggered recheck.
     */
    private void probeBackend(DCodeApiClient client) {
        setState(ConnectionState.CONNECTING, null, "");
        probe.probe(client).whenComplete((result, throwable) -> Ui.async(() -> {
            if (throwable != null) {
                setState(ConnectionState.ERROR, null, String.valueOf(throwable));
                Ui.log("error", "Connection probe failed: " + throwable.getMessage());
                return;
            }
            if (result.identity() != null && !result.identity().isDCode()
                    && localServerRunning()) {
                // The configured endpoint answers but is not a DCode server (e.g. an
                // ENR/Ollama address persisted by an earlier session). The bundled
                // product always has its own local server: switch to it instead of
                // leaving the whole UI pointed at a foreign service.
                Ui.log("warning", "Endpoint " + client.baseUrl()
                        + " is not a DCode server ("
                        + result.identity().describe()
                        + "); switching to the local embedded DCode server.");
                Preferences preferences = Preferences.userNodeForPackage(
                        RuntimeConnection.class);
                preferences.remove(PREF_ENDPOINT);
                initialize();
                return;
            }
            setState(result.state(), result.identity(), result.diagnostic());
            switch (result.state()) {
                case READY -> {
                    Ui.log("success", "DCode backend ready: "
                            + result.identity().describe());
                    Ui.clearFailureAggregates();
                    Ui.log("info", "Loading workspaces and runtimes.");
                    Ui.refreshAll();
                }
                case INITIALIZING -> Ui.log("info", result.describe());
                case DEGRADED -> Ui.log("warning", result.describe());
                case ERROR -> Ui.logDetailed("error", result.describe(), List.of(
                        "Action: check the endpoint in File ▸ Connect to Runtime… "
                                + "(is this a DCode server, not an ENR/Ollama endpoint?) "
                                + "or use Scan… to find local services",
                        "Detail: " + result.diagnostic()));
                default -> Ui.log("network", result.describe());
            }
        }));
    }

    /** True when the embedded local DCode server is actually running. */
    private static boolean localServerRunning() {
        String port = System.getProperty("dcode.server.port");
        if (port != null && !port.isBlank()) {
            return true;
        }
        var control = WebServerControlAccess.get();
        return control != null && control.isRunning();
    }

    /** User-triggered connection recheck (manual retry; no automatic retry loop). */
    public void recheck() {
        DCodeApiClient client = api.get();
        if (client == null) {
            initialize();
            return;
        }
        Ui.log("network", "Rechecking connection to " + client.baseUrl() + "…");
        probeBackend(client);
    }

    public synchronized void reconnect(String endpoint, String token) {
        Preferences preferences = Preferences.userNodeForPackage(RuntimeConnection.class);
        if (endpoint != null && !endpoint.isBlank()) {
            preferences.put(PREF_ENDPOINT, endpoint);
        }
        if (token != null) {
            writeToken(preferences, token);
        }
        DCodeSseClient old = sse.get();
        if (old != null) {
            old.close();
        }
        localMode = endpoint == null || endpoint.isBlank()
                || endpoint.equals("http://127.0.0.1:" + System.getProperty("dcode.server.port", ""));
        initialize();
    }

    /**
     * Drops the persisted remote endpoint and reconnects to the local embedded
     * server (used by the recovery actions when the remote endpoint is wrong).
     */
    public synchronized void useLocalServer() {
        Preferences preferences = Preferences.userNodeForPackage(RuntimeConnection.class);
        preferences.remove(PREF_ENDPOINT);
        DCodeSseClient old = sse.get();
        if (old != null) {
            old.close();
        }
        initialize();
    }

    /** True when the embedded DCode server is running in this process. */
    public boolean hasLocalServer() {
        String port = System.getProperty("dcode.server.port");
        return port != null && !port.isBlank();
    }

    /**
     * Base URL of the DCode web surface (web UI + help). Resolved from verified DCode
     * backends only:
     * <ol>
     *   <li>the connected backend when it is a verified DCode server (local or
     *       remote) — the web UI lives on the same HTTP server as the API;</li>
     *   <li>the embedded local server control when it is running (authoritative
     *       state — a stopped control means no local URL, even if a stale port
     *       property lingered);</li>
     *   <li>the published {@code dcode.server.port} property (only consulted when
     *       the control service is absent entirely);</li>
     *   <li>otherwise {@code null} — callers must show guidance instead of opening
     *       an unverified endpoint (e.g. an ENR/Ollama address stored as endpoint).</li>
     * </ol>
     */
    public String webBaseUrl() {
        if (state() == ConnectionState.READY && identity() != null
                && identity().isDCode()) {
            String endpoint = endpoint();
            if (endpoint != null && !endpoint.isBlank()) {
                return endpoint;
            }
        }
        var control = WebServerControlAccess.get();
        if (control != null) {
            if (!control.isRunning()) {
                return null;
            }
            String url = control.url();
            if (url != null && !url.isBlank()) {
                return url;
            }
        }
        String port = System.getProperty("dcode.server.port");
        if (port != null && !port.isBlank()) {
            return "http://127.0.0.1:" + port;
        }
        return null;
    }

    /**
     * True when the embedded local web server control exists (the server bundle is
     * active in this runtime) — the Web Interface menu shows the start/stop/restart
     * actions and "Open Web Interface" can start the server on demand.
     */
    public boolean hasLocalServerControl() {
        return WebServerControlAccess.get() != null;
    }

    public DCodeApiClient api() {
        return api.get();
    }

    public DCodeSseClient sse() {
        return sse.get();
    }

    public boolean isLocalMode() {
        return localMode;
    }

    public String endpoint() {
        DCodeApiClient client = api.get();
        return client != null ? client.baseUrl() : null;
    }

    public ConnectionState state() {
        return state.get();
    }

    public BackendIdentity identity() {
        return identity;
    }

    public String lastDiagnostic() {
        return lastDiagnostic;
    }

    /**
     * True when backend requests are worth sending. In ERROR state (unreachable,
     * wrong endpoint, contract mismatch) views must render their error state with
     * recovery actions instead of issuing requests.
     */
    public boolean isUsableForRequests() {
        return state().isUsable();
    }

    public void addStateListener(ConnectionStateListener listener) {
        stateListeners.add(listener);
    }

    public void removeStateListener(ConnectionStateListener listener) {
        stateListeners.remove(listener);
    }

    private void setState(ConnectionState newState, BackendIdentity newIdentity,
                          String diagnostic) {
        state.set(newState);
        identity = newIdentity;
        lastDiagnostic = diagnostic == null ? "" : diagnostic;
        Ui.async(() -> {
            for (ConnectionStateListener listener : stateListeners) {
                listener.stateChanged(newState, newIdentity, lastDiagnostic);
            }
        });
    }

    public synchronized void setActiveSession(Session session) {
        this.activeSession = session;
        Ui.broadcastSessionChanged(session);
    }

    public Session activeSession() {
        return activeSession;
    }

    private String resolveEndpoint(Preferences preferences) {
        String remote = preferences.get(PREF_ENDPOINT, null);
        if (remote != null && !remote.isBlank()) {
            localMode = false;
            return remote;
        }
        String port = System.getProperty("dcode.server.port");
        if (port != null && !port.isBlank()) {
            localMode = true;
            return "http://127.0.0.1:" + port;
        }
        localMode = false;
        return "http://127.0.0.1:48080";
    }

    /** Reads the token from Eclipse secure storage, falling back to preferences. */
    private String readToken(Preferences preferences) {
        try {
            var node = org.eclipse.equinox.security.storage.SecurePreferencesFactory
                    .getDefault().node(SECURE_NODE);
            String token = node.get(SECURE_TOKEN, null);
            if (token != null) {
                return token;
            }
        } catch (Exception | NoClassDefFoundError ignored) {
            // secure storage unavailable: fall through to preferences
        }
        return preferences.get(PREF_TOKEN, null);
    }

    /** Writes the token to Eclipse secure storage, falling back to preferences. */
    private void writeToken(Preferences preferences, String token) {
        try {
            var node = org.eclipse.equinox.security.storage.SecurePreferencesFactory
                    .getDefault().node(SECURE_NODE);
            node.put(SECURE_TOKEN, token, true);
            node.flush();
            preferences.remove(PREF_TOKEN);
            return;
        } catch (Exception | NoClassDefFoundError ignored) {
            // secure storage unavailable: fall back to plain preferences
        }
        preferences.put(PREF_TOKEN, token);
    }
}
