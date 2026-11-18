package eu.kalafatic.dcode.ui;

import eu.kalafatic.dcode.client.DCodeApiClient;
import eu.kalafatic.dcode.client.DCodeSseClient;
import eu.kalafatic.dcode.core.model.Session;

import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

/**
 * Runtime connection singleton: one API client + one SSE client for the whole
 * workbench. Local mode targets the embedded server (port published by the server
 * bundle); remote mode targets a user-configured endpoint. The presentation layer
 * never sees the difference.
 */
public final class RuntimeConnection {

    private static final RuntimeConnection INSTANCE = new RuntimeConnection();
    private static final String PREF_ENDPOINT = "remoteEndpoint";
    private static final String PREF_TOKEN = "authToken";

    private final AtomicReference<DCodeApiClient> api = new AtomicReference<>();
    private final AtomicReference<DCodeSseClient> sse = new AtomicReference<>();
    private volatile boolean localMode = true;
    private volatile Session activeSession;

    private RuntimeConnection() {
    }

    public static RuntimeConnection instance() {
        return INSTANCE;
    }

    /** Resolves and initializes the connection (idempotent). */
    public synchronized void initialize() {
        Preferences preferences = Preferences.userNodeForPackage(RuntimeConnection.class);
        String endpoint = resolveEndpoint(preferences);
        String token = preferences.get(PREF_TOKEN, null);
        DCodeApiClient client = new DCodeApiClient(endpoint);
        if (token != null && !token.isBlank()) {
            client.setAuthToken(token);
        }
        DCodeSseClient sseClient = new DCodeSseClient(endpoint);
        api.set(client);
        sse.set(sseClient);
        Ui.log("Runtime connection initialized: " + endpoint
                + (localMode ? " (local embedded runtime)" : " (remote)"));
    }

    public synchronized void reconnect(String endpoint, String token) {
        Preferences preferences = Preferences.userNodeForPackage(RuntimeConnection.class);
        if (endpoint != null && !endpoint.isBlank()) {
            preferences.put(PREF_ENDPOINT, endpoint);
        }
        if (token != null) {
            preferences.put(PREF_TOKEN, token);
        }
        DCodeSseClient old = sse.get();
        if (old != null) {
            old.close();
        }
        initialize();
        localMode = endpoint == null || endpoint.isBlank()
                || endpoint.equals("http://127.0.0.1:" + System.getProperty("dcode.server.port", ""));
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
}
