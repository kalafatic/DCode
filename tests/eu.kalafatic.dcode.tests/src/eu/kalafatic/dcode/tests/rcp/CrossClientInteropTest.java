package eu.kalafatic.dcode.tests.rcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dcode.client.DCodeApiClient;
import eu.kalafatic.dcode.core.event.InMemoryEventBus;
import eu.kalafatic.dcode.core.lifecycle.DefaultDCodeLifecycle;
import eu.kalafatic.dcode.core.model.RepositoryAccessPolicy;
import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.SessionState;
import eu.kalafatic.dcode.core.model.WorkspaceMode;
import eu.kalafatic.dcode.core.port.PortAllocator;
import eu.kalafatic.dcode.persistence.db.SchemaMigrationService;
import eu.kalafatic.dcode.persistence.db.SqliteDatabaseManager;
import eu.kalafatic.dcode.persistence.service.SqliteSessionService;
import eu.kalafatic.dcode.persistence.service.SqliteWorkspaceService;
import eu.kalafatic.dcode.server.DCodeApi;
import eu.kalafatic.dcode.server.DCodeHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5.10 mandatory cross-client scenarios: two independent API clients ("Web" and
 * "RCP") over one authoritative backend with durable SQLite persistence — including a
 * real server restart with both clients reconnecting to the same persisted sessions.
 */
class CrossClientInteropTest {

    @TempDir
    Path temp;

    private SqliteDatabaseManager database;
    private InMemoryEventBus bus;
    private DCodeApiClient webClient;
    private DCodeApiClient rcpClient;
    private DCodeHttpServer server;
    private String workspaceId;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        database = new SqliteDatabaseManager(temp.resolve("dcode.db"));
        new SchemaMigrationService(database).migrate();
        bus = new InMemoryEventBus(null, t -> {});
        startServer();
    }

    private void startServer() throws Exception {
        SqliteWorkspaceService workspaceService = new SqliteWorkspaceService(database, bus);
        SqliteSessionService sessionService = new SqliteSessionService(database, bus,
                workspaceService);
        var lifecycle = new DefaultDCodeLifecycle();
        lifecycle.startAll();
        var configuration = new eu.kalafatic.dcode.core.config.InMemoryConfigurationService(
                eu.kalafatic.dcode.core.config.CoreConfiguration.defaults(temp));
        var providers = new eu.kalafatic.dcode.config.provider.DefaultProviderRegistry();
        var router = new eu.kalafatic.dcode.config.routing.DefaultModelRouter();
        var toolRegistry = new eu.kalafatic.dcode.harness.tool.DefaultToolRegistry();
        toolRegistry.register(new eu.kalafatic.dcode.harness.tools.ListFilesTool());
        var toolEngine = new eu.kalafatic.dcode.harness.tool.ToolEngine(toolRegistry, bus);
        var reviewService = new eu.kalafatic.dcode.harness.change.InMemoryReviewService();
        DCodeApi api = new DCodeApi(() -> sessionService, () -> workspaceService,
                () -> reviewService, providers, toolRegistry, null, lifecycle,
                configuration, bus, () -> toolEngine, () -> null, () -> null);
        port = PortAllocator.allocate(49600, 100);
        server = new DCodeHttpServer("127.0.0.1", port, api);
        server.start(5000, false);
        webClient = new DCodeApiClient("http://127.0.0.1:" + port);
        rcpClient = new DCodeApiClient("http://127.0.0.1:" + port);
    }

    private String ensureWorkspace() throws Exception {
        if (workspaceId == null) {
            var workspace = webClient.createWorkspace("cross-client", temp.toString())
                    .get(10, TimeUnit.SECONDS);
            workspaceId = workspace.id();
            Path repoA = temp.resolve("repo-a");
            Files.createDirectories(repoA);
            var git = new eu.kalafatic.dcode.repositories.git.JGitEngine();
            git.init(repoA);
            Files.writeString(repoA.resolve("a.txt"), "a\n");
            git.commit(repoA, "seed", "t", "t@e.org");
            Path repoB = temp.resolve("repo-b");
            Files.createDirectories(repoB);
            git.init(repoB);
            Files.writeString(repoB.resolve("b.txt"), "b\n");
            git.commit(repoB, "seed", "t", "t@e.org");
            webClient.addRepository(workspaceId, "repo-a", repoA.toString(), null, false)
                    .get(10, TimeUnit.SECONDS);
            webClient.addRepository(workspaceId, "repo-b", repoB.toString(), null, true)
                    .get(10, TimeUnit.SECONDS);
        }
        return workspaceId;
    }

    @Test
    void scenarioA_webCreatesSessionRcpOpensIt() throws Exception {
        String ws = ensureWorkspace();
        var created = webClient.createSession("from-web", ws, null, null, null)
                .get(10, TimeUnit.SECONDS);
        var opened = rcpClient.getSession(created.getId()).get(10, TimeUnit.SECONDS);
        assertEquals(created.getId(), opened.getId());
        assertEquals("from-web", opened.getTitle());
    }

    @Test
    void scenarioC_rcpCreatesSessionWebOpensIt() throws Exception {
        String ws = ensureWorkspace();
        var created = rcpClient.createSession("from-rcp", ws, null, null, null)
                .get(10, TimeUnit.SECONDS);
        var opened = webClient.getSession(created.getId()).get(10, TimeUnit.SECONDS);
        assertEquals(created.getId(), opened.getId());
        assertTrue(webClient.listSessions(ws).get(10, TimeUnit.SECONDS).stream()
                .anyMatch(s -> s.getId().equals(created.getId())));
    }

    @Test
    void scenarioF_bothClientsSeeConsistentRepositoryAssociations() throws Exception {
        String ws = ensureWorkspace();
        var webView = webClient.getWorkspace(ws).get(10, TimeUnit.SECONDS);
        var rcpView = rcpClient.getWorkspace(ws).get(10, TimeUnit.SECONDS);
        assertEquals(2, webView.repositories().size());
        assertEquals(webView.repositories().size(), rcpView.repositories().size());
        boolean roMatches = webView.repositories().stream()
                .filter(r -> r.name().equals("repo-b")).allMatch(r -> r.isReadOnly());
        boolean roMatchesRcp = rcpView.repositories().stream()
                .filter(r -> r.name().equals("repo-b")).allMatch(r -> r.isReadOnly());
        assertTrue(roMatches && roMatchesRcp, "READ_ONLY policy must be consistent");
    }

    @Test
    void scenarioG_sameJavaToolingThroughBothClients() throws Exception {
        String ws = ensureWorkspace();
        var session = webClient.createSession("tooling", ws, null, null, null)
                .get(10, TimeUnit.SECONDS);
        var rcpSession = rcpClient.createSession("tooling-rcp", ws, null, null, null)
                .get(10, TimeUnit.SECONDS);
        var args = java.util.Map.<String, Object>of("path", "repo-a");
        JsonNode viaWeb = webClient.executeTool("list_files", session.getId(), args)
                .get(10, TimeUnit.SECONDS);
        JsonNode viaRcp = rcpClient.executeTool("list_files", rcpSession.getId(), args)
                .get(10, TimeUnit.SECONDS);
        assertTrue(viaWeb.path("result").path("success").asBoolean());
        assertTrue(viaRcp.path("result").path("success").asBoolean());
        assertEquals(viaWeb.path("result").path("output").toString(),
                viaRcp.path("result").path("output").toString());
    }

    @Test
    void scenarioE_serverRestartBothClientsReconnectToPersistedSessions() throws Exception {
        String ws = ensureWorkspace();
        var created = webClient.createSession("survives-restart", ws, null, null, null)
                .get(10, TimeUnit.SECONDS);

        // restart: stop the server, start a new one over the same SQLite database
        server.stop();
        startServer();

        var viaWeb = webClient.getSession(created.getId()).get(10, TimeUnit.SECONDS);
        var viaRcp = rcpClient.getSession(created.getId()).get(10, TimeUnit.SECONDS);
        assertEquals(created.getId(), viaWeb.getId());
        assertEquals(created.getId(), viaRcp.getId());
        assertEquals(SessionState.CREATED, viaRcp.getState());

        var workspaces = rcpClient.listWorkspaces().get(10, TimeUnit.SECONDS);
        assertEquals(1, workspaces.size());
        assertEquals(2, workspaces.get(0).repositories().size());
    }
}
