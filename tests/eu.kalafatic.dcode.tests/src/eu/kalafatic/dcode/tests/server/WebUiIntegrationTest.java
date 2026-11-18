package eu.kalafatic.dcode.tests.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dcode.config.provider.DefaultProviderRegistry;
import eu.kalafatic.dcode.config.routing.DefaultModelRouter;
import eu.kalafatic.dcode.core.config.CoreConfiguration;
import eu.kalafatic.dcode.core.config.InMemoryConfigurationService;
import eu.kalafatic.dcode.core.event.DCodeEventType;
import eu.kalafatic.dcode.core.event.InMemoryEventBus;
import eu.kalafatic.dcode.core.event.StandardEvent;
import eu.kalafatic.dcode.core.lifecycle.DefaultDCodeLifecycle;
import eu.kalafatic.dcode.core.model.SessionState;
import eu.kalafatic.dcode.core.port.PortAllocator;
import eu.kalafatic.dcode.harness.tool.DefaultToolRegistry;
import eu.kalafatic.dcode.harness.tool.ToolEngine;
import eu.kalafatic.dcode.harness.session.InMemorySessionService;
import eu.kalafatic.dcode.harness.change.InMemoryReviewService;
import eu.kalafatic.dcode.java.tooling.jdt.JavaWorkspaceManager;
import eu.kalafatic.dcode.java.tooling.tools.JavaDiagnosticsTool;
import eu.kalafatic.dcode.java.tooling.tools.JavaProjectStructureTool;
import eu.kalafatic.dcode.java.tooling.tools.JavaRenameTool;
import eu.kalafatic.dcode.java.tooling.tools.PdeInspectBundleTool;
import eu.kalafatic.dcode.repositories.git.JGitEngine;
import eu.kalafatic.dcode.repositories.service.RepositoryOperations;
import eu.kalafatic.dcode.repositories.workspace.RepositoryWorkspaceManager;
import eu.kalafatic.dcode.server.DCodeApi;
import eu.kalafatic.dcode.server.DCodeHttpServer;
import eu.kalafatic.dcode.server.StaticContent;
import eu.kalafatic.dcode.tests.javatoo.JavaToolingFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5.9 gate: the web-UI backend surface — static assets, file browsing, tool
 * execution (incl. the WP5.6 JDT tools), changes/diffs, SSE streams and auth — all over
 * real HTTP without any browser dependency.
 */
class WebUiIntegrationTest {

    private DCodeHttpServer server;
    private String baseUrl;
    private HttpClient client;
    private ObjectMapper mapper = new ObjectMapper();
    private InMemorySessionService sessionService;
    private InMemoryConfigurationService configuration;
    private JavaToolingFixtures fixtures;
    private InMemoryEventBus bus;
    private Path webRoot;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new JavaToolingFixtures();

        bus = new InMemoryEventBus(null, t -> {});
        var workspaceService = fixtures.workspaceService;
        sessionService = new InMemorySessionService(bus, workspaceService);

        JGitEngine gitEngine = new JGitEngine();
        // seed the fixture repositories as real git repositories so the changes
        // endpoint (live git state) has something authoritative to report
        gitEngine.init(fixtures.root.resolve("javarepo"));
        gitEngine.commit(fixtures.root.resolve("javarepo"), "fixture seed",
                "web-test", "web@dcode.local");
        RepositoryWorkspaceManager workspaceManager = new RepositoryWorkspaceManager(gitEngine);
        RepositoryOperations operations = new RepositoryOperations(gitEngine,
                workspaceManager::repositoryDir);

        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new JavaProjectStructureTool(fixtures.workspaceManager));
        toolRegistry.register(new JavaDiagnosticsTool(fixtures.workspaceManager));
        toolRegistry.register(new JavaRenameTool(fixtures.workspaceManager));
        toolRegistry.register(new PdeInspectBundleTool(fixtures.workspaceManager));
        ToolEngine toolEngine = new ToolEngine(toolRegistry, bus);

        var providers = new DefaultProviderRegistry();
        var router = new DefaultModelRouter();
        var reviewService = new InMemoryReviewService();
        var lifecycle = new DefaultDCodeLifecycle();
        lifecycle.startAll();
        configuration = new InMemoryConfigurationService(
                CoreConfiguration.defaults(fixtures.root));

        DCodeApi api = new DCodeApi(() -> sessionService, () -> workspaceService,
                () -> reviewService, providers, toolRegistry,
                null, lifecycle, configuration, bus,
                () -> toolEngine, () -> operations, () -> null);

        // minimal static fixture for serving tests
        webRoot = Files.createTempDirectory("dcode-web-root");
        Files.writeString(webRoot.resolve("index.html"),
                "<!DOCTYPE html><html><head><title>DCode</title></head><body>web</body></html>");
        Files.createDirectories(webRoot.resolve("css"));
        Files.writeString(webRoot.resolve("css/app.css"), "body { color: #d7dce3; }");

        int port = PortAllocator.allocate(49400, 100);
        server = new DCodeHttpServer("127.0.0.1", port, api,
                new StaticContent.FileSystem(webRoot));
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

    private record ApiResponse(int status, String body) {
    }

    private ApiResponse request(String method, String path, String body, String token)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        return new ApiResponse(response.statusCode(), response.body());
    }

    private JsonNode okJson(String method, String path, String body) throws Exception {
        ApiResponse response = request(method, path, body, null);
        assertEquals(200, response.status(), path + " -> " + response.body());
        return mapper.readTree(response.body());
    }

    @Test
    void servesStaticAssets() throws Exception {
        ApiResponse index = request("GET", "/", null, null);
        assertEquals(200, index.status());
        assertTrue(index.body().contains("DCode"));
        assertTrue(index.body().contains("<html"));

        ApiResponse css = request("GET", "/css/app.css", null, null);
        assertEquals(200, css.status());
        assertTrue(css.body().contains("color"));

        // SPA fallback for unknown paths
        ApiResponse unknown = request("GET", "/some/deep/route", null, null);
        assertEquals(200, unknown.status());
        assertTrue(unknown.body().contains("<html"));

        // traversal attempts are neutralized
        ApiResponse traversal = request("GET", "/../pom.xml", null, null);
        assertEquals(200, traversal.status());
        assertTrue(traversal.body().contains("<html"));
    }

    @Test
    void fileTreeAndReadWithContainment() throws Exception {
        String workspaceId = fixtures.workspace.id();
        JsonNode tree = okJson("GET", "/api/v1/workspaces/" + workspaceId
                + "/tree?path=javarepo&depth=2", null);
        assertTrue(tree.path("entryCount").asInt() > 0, tree.toString());
        boolean foundCore = false;
        for (JsonNode entry : tree.path("entries")) {
            if (entry.path("name").asText().equals("core")
                    && entry.path("type").asText().equals("dir")) {
                foundCore = true;
            }
        }
        assertTrue(foundCore);

        JsonNode file = okJson("GET", "/api/v1/workspaces/" + workspaceId
                + "/files?path=javarepo/core/src/main/java/com/example/core/Greeting.java", null);
        assertTrue(file.path("content").asText().contains("interface Greeting"));

        ApiResponse badPath = request("GET", "/api/v1/workspaces/" + workspaceId
                + "/files?path=../../etc/passwd", null, null);
        assertEquals(400, badPath.status());
        assertTrue(badPath.body().contains("VALIDATION_FAILED")
                || badPath.body().contains("escapes"), badPath.body());

        ApiResponse missing = request("GET", "/api/v1/workspaces/" + workspaceId
                + "/files?path=nope.txt", null, null);
        assertEquals(404, missing.status());
    }

    @Test
    void javaToolingOverHttp() throws Exception {
        String sessionId = sessionService.createSession(
                new eu.kalafatic.dcode.core.service.SessionService.CreateSessionRequest(
                        "web-tools", fixtures.workspace.id(), null, null, null, null))
                .getId();

        JsonNode structure = okJson("POST", "/api/v1/tools/java_project_structure/execute",
                "{\"sessionId\":\"" + sessionId + "\",\"repository\":\"javarepo\"}");
        assertEquals("COMPLETED", structure.path("toolCall").path("state").asText(),
                structure.toString());
        assertEquals(3, structure.path("result").path("output").path("projectCount").asInt(),
                structure.toString());

        JsonNode diagnostics = okJson("POST", "/api/v1/tools/java_get_diagnostics/execute",
                "{\"sessionId\":\"" + sessionId + "\",\"repository\":\"javarepo\","
                        + "\"module\":\"core\"}");
        assertEquals(0, diagnostics.path("result").path("output").path("errorCount").asInt());

        JsonNode pde = okJson("POST", "/api/v1/tools/pde_inspect_bundle/execute",
                "{\"sessionId\":\"" + sessionId + "\",\"path\":\"osgirepo/com.example.api\"}");
        assertEquals("com.example.api", pde.path("result").path("output").path("symbolicName").asText());
    }

    @Test
    void renameOverHttpRequiresRunningSessionAndPausesAfter() throws Exception {
        String sessionId = sessionService.createSession(
                new eu.kalafatic.dcode.core.service.SessionService.CreateSessionRequest(
                        "web-rename", fixtures.workspace.id(), null, null, null, null))
                .getId();
        String filePath = "javarepo/core/src/main/java/com/example/core/GreetingImpl.java";

        JsonNode rename = okJson("POST", "/api/v1/tools/java_rename/execute",
                "{\"sessionId\":\"" + sessionId + "\",\"path\":\"" + filePath + "\","
                        + "\"kind\":\"type\",\"name\":\"GreetingImpl\","
                        + "\"newName\":\"WebGreeting\"}");
        assertEquals("COMPLETED", rename.path("toolCall").path("state").asText(),
                rename.toString());
        assertEquals(0, rename.path("result").path("output").path("errorCount").asInt(), rename.toString());
        assertTrue(rename.path("result").path("output").path("changePerformed").asBoolean(), rename.toString());

        // session was driven through RUNNING and paused at SUSPENDED (documented behavior)
        JsonNode session = okJson("GET", "/api/v1/sessions/" + sessionId, null);
        assertEquals("SUSPENDED", session.path("state").asText());

        assertTrue(Files.exists(fixtures.root.resolve(
                "javarepo/core/src/main/java/com/example/core/WebGreeting.java")));

        // changes endpoint reports the live git state
        JsonNode changes = okJson("GET", "/api/v1/sessions/" + sessionId + "/changes", null);
        boolean changedReported = false;
        for (JsonNode repo : changes.path("repositories")) {
            for (JsonNode changed : repo.path("changedFiles")) {
                if (changed.asText().contains("WebGreeting.java")) {
                    changedReported = true;
                }
            }
        }
        assertTrue(changedReported, changes.toString());
    }

    @Test
    void mutatingToolOnTerminalSessionIsRejected() throws Exception {
        String sessionId = sessionService.createSession(
                new eu.kalafatic.dcode.core.service.SessionService.CreateSessionRequest(
                        "done", fixtures.workspace.id(), null, null, null, null)).getId();
        sessionService.transition(sessionId, SessionState.RUNNING);
        sessionService.transition(sessionId, SessionState.COMPLETED);

        ApiResponse response = request("POST", "/api/v1/tools/java_rename/execute",
                "{\"sessionId\":\"" + sessionId + "\",\"path\":\"x\",\"kind\":\"type\","
                        + "\"name\":\"A\",\"newName\":\"B\"}", null);
        assertEquals(400, response.status());
        assertTrue(response.body().contains("COMPLETED"));
    }

    @Test
    void sseStreamDeliversTypedEventsWithMonotonicIds() throws Exception {
        String sessionId = sessionService.createSession(
                new eu.kalafatic.dcode.core.service.SessionService.CreateSessionRequest(
                        "sse-test", fixtures.workspace.id(), null, null, null, null)).getId();

        HttpRequest sseRequest = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/api/v1/events/stream?sessionId=" + sessionId))
                .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<java.io.InputStream> sseResponse = client.send(sseRequest,
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, sseResponse.statusCode());

        java.io.InputStream stream = sseResponse.body();
        byte[] buffer = new byte[4096];

        bus.publish(StandardEvent.of(DCodeEventType.SESSION_STATE_CHANGED, sessionId,
                "{\"from\":\"CREATED\",\"to\":\"RUNNING\"}"));
        bus.publish(StandardEvent.of(DCodeEventType.MESSAGE_APPENDED, sessionId,
                "{\"role\":\"USER\"}"));

        String collected = new String(stream.readNBytes(1), StandardCharsets.UTF_8);
        long deadline = System.currentTimeMillis() + 10000;
        while ((!collected.contains("event: SESSION_STATE_CHANGED")
                || !collected.contains("\"CREATED\""))
                && System.currentTimeMillis() < deadline) {
            collected += new String(stream.readNBytes(1), StandardCharsets.UTF_8);
        }
        assertTrue(collected.contains("event: SESSION_STATE_CHANGED"),
                "stream content: " + collected);
        assertTrue(collected.contains("data: {\"from\":\"CREATED\""),
                "stream content: " + collected);

        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("id: (\\d+)").matcher(collected);
        long previous = -1;
        while (matcher.find()) {
            long current = Long.parseLong(matcher.group(1));
            assertTrue(current > previous, "event ids must be strictly increasing");
            previous = current;
        }
        stream.close();
    }

    @Test
    void authEnforcedWhenTokenConfigured() throws Exception {
        configuration.setSecret("server.authToken", "web-token");
        ApiResponse denied = request("GET", "/api/v1/workspaces", null, null);
        assertEquals(401, denied.status());
        assertEquals("AUTHENTICATION_FAILED",
                mapper.readTree(denied.body()).path("code").asText());

        ApiResponse allowed = request("GET", "/api/v1/workspaces", null, "web-token");
        assertEquals(200, allowed.status());
        configuration.removeSecret("server.authToken");
    }

    @Test
    void toolEndpointRequiresSessionAndKnownTool() throws Exception {
        ApiResponse missingSession = request("POST",
                "/api/v1/tools/java_project_structure/execute", "{}", null);
        assertEquals(400, missingSession.status());
        assertTrue(missingSession.body().contains("sessionId"));

        String sessionId = sessionService.createSession(
                new eu.kalafatic.dcode.core.service.SessionService.CreateSessionRequest(
                        "t", fixtures.workspace.id(), null, null, null, null)).getId();
        ApiResponse unknown = request("POST", "/api/v1/tools/not_a_tool/execute",
                "{\"sessionId\":\"" + sessionId + "\"}", null);
        assertEquals(404, unknown.status());
        assertEquals("NOT_FOUND", mapper.readTree(unknown.body()).path("code").asText());
    }
}
