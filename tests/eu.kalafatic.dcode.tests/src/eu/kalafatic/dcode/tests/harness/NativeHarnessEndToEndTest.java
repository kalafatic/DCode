package eu.kalafatic.dcode.tests.harness;

import eu.kalafatic.dcode.config.provider.DefaultProviderRegistry;
import eu.kalafatic.dcode.config.routing.DefaultModelRouter;
import eu.kalafatic.dcode.core.event.InMemoryEventBus;
import eu.kalafatic.dcode.core.model.RepositoryAccessPolicy;
import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.SessionState;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.core.model.WorkspaceMode;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.harness.agent.AgentEngine;
import eu.kalafatic.dcode.harness.change.ChangeManager;
import eu.kalafatic.dcode.harness.change.InMemoryReviewService;
import eu.kalafatic.dcode.core.service.ReviewService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5.5 gate: a real coding task (file creation + git inspection) completes end-to-end
 * using the native DCode harness. No OpenCode or external harness is involved; the LLM
 * is a deterministic fake, the tools and repositories are real.
 */
class NativeHarnessEndToEndTest {

    @TempDir
    Path temp;

    private SessionExecutor executor;
    private InMemorySessionService sessionService;
    private ReviewService reviewService;
    private ToolEngine toolEngine;
    private Path repoDir;
    private Workspace workspace;
    private DefaultProviderRegistry providers;

    @BeforeEach
    void setUp() throws Exception {
        Path root = temp.resolve("workspace");
        Files.createDirectories(root);
        repoDir = root.resolve("project");
        JGitEngine gitEngine = new JGitEngine();
        gitEngine.init(repoDir);
        Files.writeString(repoDir.resolve("README.md"), "# Project\n");
        gitEngine.commit(repoDir, "seed", "Test", "t@example.org");

        RepositoryWorkspaceManager workspaceManager = new RepositoryWorkspaceManager(gitEngine);
        InMemoryEventBus bus = new InMemoryEventBus(null, t -> {});
        InMemoryWorkspaceService workspaceService = new InMemoryWorkspaceService(bus);
        workspace = workspaceService.createWorkspace("e2e", WorkspaceMode.LOCAL,
                root.toString());
        RepositoryRef repo = new RepositoryRef("p1", "project", repoDir.toString(), null,
                "master", RepositoryAccessPolicy.READ_WRITE, Instant.now());
        workspaceService.addRepository(workspace.id(), repo);

        RepositoryOperations operations = new RepositoryOperations(gitEngine,
                workspaceManager::repositoryDir);

        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new ListFilesTool());
        toolRegistry.register(new ReadFileTool());
        toolRegistry.register(new WriteFileTool());
        toolRegistry.register(new GitStatusTool());
        toolEngine = new ToolEngine(toolRegistry, bus);

        sessionService = new InMemorySessionService(bus, workspaceService);

        DefaultProviderRegistry providerRegistry = new DefaultProviderRegistry();
        providerRegistry.register(new FakeLlmProvider(
                "{\"tool\":\"list_files\",\"arguments\":{\"path\":\"project\"}}",
                "{\"tool\":\"write_file\",\"arguments\":{\"path\":\"project/src/Greeter.java\","
                        + "\"content\":\"public class Greeter { public String greet() { return \\\"Hello\\\"; } }\"}}",
                "{\"tool\":\"git_status\",\"arguments\":{\"repository\":\"project\"}}",
                "{\"final\":\"Created Greeter class in project; no build tool configured.\"}"));
        this.providers = providerRegistry;
        DefaultModelRouter router = new DefaultModelRouter();
        router.setDefaultModel("fake", "fake-model");

        AgentEngine agentEngine = new AgentEngine(bus);
        ContextEngine contextEngine = new ContextEngine(operations);
        ToolCallingAgentLoop loop = new ToolCallingAgentLoop(providerRegistry, toolEngine,
                toolRegistry, contextEngine, sessionService);
        ChangeManager changeManager = new ChangeManager(operations);
        reviewService = new InMemoryReviewService();
        executor = new SessionExecutor(sessionService, workspaceService, reviewService,
                providerRegistry, router, operations, null, agentEngine, loop, changeManager, bus);
    }

    @Test
    void completesCodingTaskNatively() throws Exception {
        var session = sessionService.createSession(new SessionService.CreateSessionRequest(
                "Create Greeter", workspace.id(), null, null, null, null));
        assertEquals(SessionState.CREATED, session.getState());

        executor.submit(session.getId(), "Create a Greeter class with a greet() method.");

        var finalSession = awaitSessionState(session.getId());
        assertEquals(SessionState.WAITING_FOR_APPROVAL, finalSession.getState());

        assertTrue(Files.exists(repoDir.resolve("src/Greeter.java")));
        String content = Files.readString(repoDir.resolve("src/Greeter.java"));
        assertTrue(content.contains("greet()"));

        var toolCalls = toolEngine.callsOf(session.getId());
        assertEquals(3, toolCalls.size());
        assertEquals("list_files", toolCalls.get(0).toolName());
        assertEquals("write_file", toolCalls.get(1).toolName());
        assertEquals("git_status", toolCalls.get(2).toolName());

        assertFalse(reviewService.reviewsOf(session.getId()).isEmpty());
        var review = reviewService.reviewsOf(session.getId()).get(0);
        assertEquals(ReviewService.Status.PENDING, review.status());

        var messages = sessionService.listMessages(session.getId());
        assertTrue(messages.stream().anyMatch(m ->
                m.role() == eu.kalafatic.dcode.core.model.MessageRole.USER));
        assertTrue(messages.stream().anyMatch(m ->
                m.role() == eu.kalafatic.dcode.core.model.MessageRole.TOOL));
    }

    @Test
    void failedExecutionMarksSessionFailed() throws Exception {
        providers.register(FakeLlmProvider.throwing());
        var session = sessionService.createSession(new SessionService.CreateSessionRequest(
                "doomed", workspace.id(), "fake-throwing", null, null, null));
        executor.submit(session.getId(), "do something");
        var finalSession = awaitSessionState(session.getId());
        assertEquals(SessionState.FAILED, finalSession.getState());
    }

    private eu.kalafatic.dcode.core.model.Session awaitSessionState(String sessionId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            var session = sessionService.getSession(sessionId).orElseThrow();
            if (session.getState().isTerminal()
                    || session.getState() == SessionState.WAITING_FOR_APPROVAL) {
                return session;
            }
            Thread.sleep(50);
        }
        return sessionService.getSession(sessionId).orElseThrow();
    }
}
