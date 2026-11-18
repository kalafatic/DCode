package eu.kalafatic.dcode.tests.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dcode.config.provider.DefaultProviderRegistry;
import eu.kalafatic.dcode.config.routing.DefaultModelRouter;
import eu.kalafatic.dcode.core.config.CoreConfiguration;
import eu.kalafatic.dcode.core.config.InMemoryConfigurationService;
import eu.kalafatic.dcode.core.event.InMemoryEventBus;
import eu.kalafatic.dcode.core.lifecycle.DefaultDCodeLifecycle;
import eu.kalafatic.dcode.core.model.RepositoryAccessPolicy;
import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.WorkspaceMode;
import eu.kalafatic.dcode.core.port.PortAllocator;
import eu.kalafatic.dcode.harness.agent.AgentEngine;
import eu.kalafatic.dcode.harness.change.ChangeManager;
import eu.kalafatic.dcode.harness.change.InMemoryReviewService;
import eu.kalafatic.dcode.harness.context.ContextEngine;
import eu.kalafatic.dcode.harness.execution.SessionExecutor;
import eu.kalafatic.dcode.harness.orchestration.ToolCallingAgentLoop;
import eu.kalafatic.dcode.harness.session.InMemorySessionService;
import eu.kalafatic.dcode.harness.tool.DefaultToolRegistry;
import eu.kalafatic.dcode.harness.tool.ToolEngine;
import eu.kalafatic.dcode.harness.tools.GitStatusTool;
import eu.kalafatic.dcode.harness.tools.ListFilesTool;
import eu.kalafatic.dcode.harness.tools.ReadFileTool;
import eu.kalafatic.dcode.harness.tools.WriteFileTool;
import eu.kalafatic.dcode.repositories.git.JGitEngine;
import eu.kalafatic.dcode.repositories.service.InMemoryWorkspaceService;
import eu.kalafatic.dcode.repositories.service.RepositoryOperations;
import eu.kalafatic.dcode.repositories.workspace.RepositoryWorkspaceManager;
import eu.kalafatic.dcode.server.DCodeApi;
import eu.kalafatic.dcode.server.DCodeHttpServer;
import eu.kalafatic.dcode.tests.harness.FakeLlmProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5.8 gate: a full coding session is executed through HTTP without SWT or Workbench
 * initialization. The stack is wired in-process (no OSGi, no UI bundles on the path).
 */
@SuppressWarnings("restriction")
class HeadlessApiEndToEndTest {

    @TempDir
    Path temp;

    private DCodeHttpServer server;
    private String baseUrl;
    private HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private InMemorySessionService sessionService;
    private InMemoryWorkspaceService workspaceService;
    private SessionExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        Path root = temp.resolve("workspace");
        Files.createDirectories(root);
        Path repoDir = root.resolve("project");
        JGitEngine gitEngine = new JGitEngine();
        gitEngine.init(repoDir);
        Files.writeString(repoDir.resolve("README.md"), "# Project\n");
        gitEngine.commit(repoDir, "seed", "T", "t@example.org");

        InMemoryEventBus bus = new InMemoryEventBus(null, t -> {});
        workspaceService = new InMemoryWorkspaceService(bus);
        var workspace = workspaceService.createWorkspace("api-e2e", WorkspaceMode.LOCAL,
                root.toString());
        workspaceService.addRepository(workspace.id(), new RepositoryRef("p1", "project",
                repoDir.toString(), null, "master", RepositoryAccessPolicy.READ_WRITE,
                Instant.now()));

        RepositoryWorkspaceManager workspaceManager = new RepositoryWorkspaceManager(gitEngine);
        RepositoryOperations operations = new RepositoryOperations(gitEngine,
                workspaceManager::repositoryDir);
        sessionService = new InMemorySessionService(bus, workspaceService);

        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new ListFilesTool());
        toolRegistry.register(new ReadFileTool());
        toolRegistry.register(new WriteFileTool());
        toolRegistry.register(new GitStatusTool());
        ToolEngine toolEngine = new ToolEngine(toolRegistry, bus);

        DefaultProviderRegistry providers = new DefaultProviderRegistry();
        providers.register(new FakeLlmProvider(
                "{\"tool\":\"write_file\",\"arguments\":{\"path\":\"project/api-created.txt\","
                        + "\"content\":\"created over http\"}}",
                "{\"final\":\"Created api-created.txt\"}"));
        DefaultModelRouter router = new DefaultModelRouter();
        router.setDefaultModel("fake", "fake-model");

        AgentEngine agentEngine = new AgentEngine(bus);
        ContextEngine contextEngine = new ContextEngine(operations);
        ToolCallingAgentLoop loop = new ToolCallingAgentLoop(providers, toolEngine,
                toolRegistry, contextEngine, sessionService);
        ChangeManager changeManager = new ChangeManager(operations);
        var reviewService = new InMemoryReviewService();
        executor = new SessionExecutor(sessionService, workspaceService, reviewService,
                providers, router, operations, null, agentEngine, loop, changeManager, bus);

        DefaultDCodeLifecycle lifecycle = new DefaultDCodeLifecycle();
        lifecycle.startAll();
        InMemoryConfigurationService configuration =
                new InMemoryConfigurationService(CoreConfiguration.defaults(temp));
        DCodeApi api = new DCodeApi(() -> sessionService, () -> workspaceService,
                () -> reviewService, providers, toolRegistry, executor, lifecycle,
                configuration, bus);

        int port = PortAllocator.allocate(49300, 100);
        server = new DCodeHttpServer("127.0.0.1", port, api);
        server.start(5000, false);
        baseUrl = "http://127.0.0.1:" + port;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private record ApiResponse(int status, JsonNode body) {
    }

    private ApiResponse request(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20));
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode json = response.body() == null || response.body().isBlank()
                ? mapper.createObjectNode() : mapper.readTree(response.body());
        return new ApiResponse(response.statusCode(), json);
    }

    private JsonNode ok(String method, String path, String body) throws Exception {
        ApiResponse response = request(method, path, body);
        assertEquals(200, response.status(),
                "HTTP " + response.status() + " for " + method + " " + path + ": "
                        + response.body());
        return response.body();
    }

    @Test
    void fullCodingSessionOverHttp() throws Exception {
        JsonNode health = ok("GET", "/api/v1/health", null);
        assertTrue(health.path("ready").asBoolean());
        assertEquals("DCODE", health.path("application").asText());

        JsonNode tools = ok("GET", "/api/v1/tools", null);
        assertTrue(tools.size() >= 4);

        JsonNode workspaces = ok("GET", "/api/v1/workspaces", null);
        assertEquals(1, workspaces.size());
        String workspaceId = workspaces.get(0).path("id").asText();
        assertEquals(1, workspaces.get(0).path("repositories").size());

        JsonNode session = ok("POST", "/api/v1/sessions",
                "{\"title\":\"http task\",\"workspaceId\":\"" + workspaceId + "\"}");
        String sessionId = session.path("id").asText();
        assertEquals("CREATED", session.path("state").asText());

        ok("POST", "/api/v1/sessions/" + sessionId + "/start",
                "{\"task\":\"Create a marker file\"}");

        String state = "";
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode current = ok("GET", "/api/v1/sessions/" + sessionId, null);
            state = current.path("state").asText();
            if (state.equals("WAITING_FOR_APPROVAL") || state.equals("FAILED")
                    || state.equals("COMPLETED")) {
                break;
            }
            Thread.sleep(100);
        }
        assertEquals("WAITING_FOR_APPROVAL", state);

        assertTrue(Files.exists(temp.resolve("workspace/project/api-created.txt")));

        JsonNode reviews = ok("GET", "/api/v1/sessions/" + sessionId + "/reviews", null);
        assertEquals(1, reviews.size());
        String reviewId = reviews.get(0).path("id").asText();
        assertEquals("PENDING", reviews.get(0).path("status").asText());

        JsonNode approved = ok("POST", "/api/v1/reviews/" + reviewId + "/approve",
                "{\"approverId\":\"tester\"}");
        assertEquals("APPROVED", approved.path("status").asText());

        JsonNode messages = ok("GET", "/api/v1/sessions/" + sessionId + "/messages", null);
        assertTrue(messages.size() >= 3);
        assertFalse(messages.toString().contains("sk-"));

        ApiResponse notFound = request("GET", "/api/v1/sessions/nope", null);
        assertEquals(404, notFound.status());
        assertEquals("NOT_FOUND", notFound.body().path("code").asText());
    }

    @Test
    void authLogicRequiresTokenOnlyWhenConfigured() {
        InMemoryConfigurationService configuration =
                new InMemoryConfigurationService(CoreConfiguration.defaults(temp));
        DefaultDCodeLifecycle lifecycle = new DefaultDCodeLifecycle();
        try {
            lifecycle.startAll();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        InMemoryEventBus bus = new InMemoryEventBus(null, t -> {});
        DCodeApi api = new DCodeApi(() -> sessionService, () -> workspaceService,
                () -> new InMemoryReviewService(), new DefaultProviderRegistry(),
                new DefaultToolRegistry(), executor, lifecycle, configuration, bus);

        assertTrue(api.isAuthorized(null));
        assertTrue(api.isAuthorized("anything"));
        configuration.setSecret("server.authToken", "secret-token");
        assertFalse(api.isAuthorized(null));
        assertFalse(api.isAuthorized("Bearer wrong"));
        assertTrue(api.isAuthorized("Bearer secret-token"));
    }
}
