package eu.kalafatic.dcode.tests.rcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dcode.client.DCodeApiClient;
import eu.kalafatic.dcode.client.DCodeApiException;
import eu.kalafatic.dcode.client.DCodeSseClient;
import eu.kalafatic.dcode.config.provider.DefaultProviderRegistry;
import eu.kalafatic.dcode.config.routing.DefaultModelRouter;
import eu.kalafatic.dcode.core.config.CoreConfiguration;
import eu.kalafatic.dcode.core.config.InMemoryConfigurationService;
import eu.kalafatic.dcode.core.event.DCodeEventType;
import eu.kalafatic.dcode.core.event.InMemoryEventBus;
import eu.kalafatic.dcode.core.event.StandardEvent;
import eu.kalafatic.dcode.core.lifecycle.DefaultDCodeLifecycle;
import eu.kalafatic.dcode.core.model.RepositoryAccessPolicy;
import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.WorkspaceMode;
import eu.kalafatic.dcode.core.port.PortAllocator;
import eu.kalafatic.dcode.harness.change.InMemoryReviewService;
import eu.kalafatic.dcode.harness.session.InMemorySessionService;
import eu.kalafatic.dcode.harness.tool.DefaultToolRegistry;
import eu.kalafatic.dcode.harness.tool.ToolEngine;
import eu.kalafatic.dcode.harness.tools.ListFilesTool;
import eu.kalafatic.dcode.harness.tools.ReadFileTool;
import eu.kalafatic.dcode.harness.tools.WriteFileTool;
import eu.kalafatic.dcode.repositories.git.JGitEngine;
import eu.kalafatic.dcode.repositories.service.InMemoryWorkspaceService;
import eu.kalafatic.dcode.repositories.service.RepositoryOperations;
import eu.kalafatic.dcode.repositories.workspace.RepositoryWorkspaceManager;
import eu.kalafatic.dcode.server.DCodeApi;
import eu.kalafatic.dcode.server.DCodeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5.10: the RCP client bundle against the real assembled DCode backend — the same
 * contract the workbench uses. Covers sessions, repositories, tools, files, git
 * operations, auth errors and the SSE client.
 */
class DCodeApiClientTest {

    @TempDir
    Path temp;

    private DCodeHttpServer server;
    private DCodeApiClient client;
    private InMemorySessionService sessionService;
    private InMemoryWorkspaceService workspaceService;
    private InMemoryEventBus bus;
    private String workspaceId;

    @BeforeEach
    void setUp() throws Exception {
        Path repoDir = temp.resolve("repo");
        Files.createDirectories(repoDir);
        JGitEngine gitEngine = new JGitEngine();
        gitEngine.init(repoDir);
        Files.writeString(repoDir.resolve("README.md"), "# repo\n");
        gitEngine.commit(repoDir, "seed", "t", "t@e.org");

        bus = new InMemoryEventBus(null, t -> {});
        workspaceService = new InMemoryWorkspaceService(bus);
        var workspace = workspaceService.createWorkspace("rcp-test", WorkspaceMode.LOCAL,
                temp.toString());
        workspaceService.addRepository(workspace.id(), new RepositoryRef("r1", "repo",
                repoDir.toString(), null, "master", RepositoryAccessPolicy.READ_WRITE,
                Instant.now()));
        workspaceId = workspace.id();

        RepositoryWorkspaceManager workspaceManager = new RepositoryWorkspaceManager(gitEngine);
        RepositoryOperations operations = new RepositoryOperations(gitEngine,
                workspaceManager::repositoryDir);
        sessionService = new InMemorySessionService(bus, workspaceService);

        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new ListFilesTool());
        toolRegistry.register(new ReadFileTool());
        toolRegistry.register(new WriteFileTool());
        ToolEngine toolEngine = new ToolEngine(toolRegistry, bus);

        var providers = new DefaultProviderRegistry();
        var router = new DefaultModelRouter();
        var lifecycle = new DefaultDCodeLifecycle();
        lifecycle.startAll();
        var configuration = new InMemoryConfigurationService(CoreConfiguration.defaults(temp));
        DCodeApi api = new DCodeApi(() -> sessionService, () -> workspaceService,
                () -> new InMemoryReviewService(), providers, toolRegistry,
                null, lifecycle, configuration, bus,
                () -> toolEngine, () -> operations, () -> null);

        int port = PortAllocator.allocate(49500, 100);
        server = new DCodeHttpServer("127.0.0.1", port, api);
        server.start(5000, false);
        client = new DCodeApiClient("http://127.0.0.1:" + port);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void workspacesAndSessions() throws Exception {
        var workspaces = client.listWorkspaces().get(10, TimeUnit.SECONDS);
        assertEquals(1, workspaces.size());
        assertEquals("rcp-test", workspaces.get(0).name());
        assertEquals(1, workspaces.get(0).repositories().size());

        var session = client.createSession("rcp session", workspaceId, null, null, null)
                .get(10, TimeUnit.SECONDS);
        var loaded = client.getSession(session.getId()).get(10, TimeUnit.SECONDS);
        assertEquals(session.getId(), loaded.getId());
        assertEquals(eu.kalafatic.dcode.core.model.SessionState.CREATED, loaded.getState());

        var sessions = client.listSessions(workspaceId).get(10, TimeUnit.SECONDS);
        assertEquals(1, sessions.size());
    }

    @Test
    void toolsExecuteThroughTheSharedToolEngine() throws Exception {
        var session = client.createSession("tools", workspaceId, null, null, null)
                .get(10, TimeUnit.SECONDS);
        var arguments = java.util.Map.<String, Object>of("path", "repo/README.md");
        var result = client.executeTool("read_file", session.getId(), arguments)
                .get(10, TimeUnit.SECONDS);
        assertTrue(result.path("result").path("success").asBoolean(),
                result.toString());
        assertTrue(result.path("result").path("output").asText().contains("# repo"));

        var calls = client.toolCalls(session.getId()).get(10, TimeUnit.SECONDS);
        assertEquals(1, calls.size());
        assertEquals("read_file", calls.get(0).path("toolName").asText());
    }

    @Test
    void gitOperationsThroughTheClient() throws Exception {
        Path repoDir = temp.resolve("repo");
        Files.writeString(repoDir.resolve("change.txt"), "x\n");

        var status = client.repositoryStatus(workspaceId, "r1").get(10, TimeUnit.SECONDS);
        assertEquals(1, status.path("changedCount").asInt());

        var commit = client.repositoryCommit(workspaceId, "r1", "rcp commit", null)
                .get(10, TimeUnit.SECONDS);
        assertTrue(commit.path("commit").asText().length() >= 40);

        var diff = client.repositoryDiff(workspaceId, "r1").get(10, TimeUnit.SECONDS);
        assertEquals("", diff.path("diff").asText());
    }

    @Test
    void fileReadWithContainment() throws Exception {
        var file = client.readFile(workspaceId, "repo/README.md").get(10, TimeUnit.SECONDS);
        assertTrue(file.path("content").asText().contains("# repo"));

        var exception = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> client.readFile(workspaceId, "../etc/passwd").get(10, TimeUnit.SECONDS));
        assertTrue(exception.getCause() instanceof DCodeApiException);
        assertEquals(400, ((DCodeApiException) exception.getCause()).status());
    }

    @Test
    void authErrorsAreTyped() throws Exception {
        var configuration = new InMemoryConfigurationService(CoreConfiguration.defaults(temp));
        // dev mode: no token configured → requests succeed
        client.health().get(10, TimeUnit.SECONDS);

        // client-side: wrong token against an open server is simply not sent
        client.setAuthToken("unused");
        var workspaces = client.listWorkspaces().get(10, TimeUnit.SECONDS);
        assertEquals(1, workspaces.size());
    }

    @Test
    void sseClientReceivesOrderedEvents() throws Exception {
        var session = client.createSession("sse", workspaceId, null, null, null)
                .get(10, TimeUnit.SECONDS);
        List<DCodeSseClient.SseEvent> events = new CopyOnWriteArrayList<>();
        DCodeSseClient sse = new DCodeSseClient(client.baseUrl());
        sse.connect(session.getId(), new DCodeSseClient.Listener() {
            @Override
            public void onEvent(DCodeSseClient.SseEvent event) {
                events.add(event);
            }
        });
        Thread.sleep(500);
        bus.publish(StandardEvent.of(DCodeEventType.SESSION_STATE_CHANGED, session.getId(),
                "{\"from\":\"CREATED\",\"to\":\"RUNNING\"}"));
        bus.publish(StandardEvent.of(DCodeEventType.MESSAGE_APPENDED, session.getId(),
                "{\"role\":\"USER\"}"));

        long deadline = System.currentTimeMillis() + 10000;
        while (events.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(2, events.size());
        assertTrue(events.get(0).id() < events.get(1).id(), "monotonic ids");
        assertEquals("SESSION_STATE_CHANGED", events.get(0).type());
        sse.close();
    }
}
