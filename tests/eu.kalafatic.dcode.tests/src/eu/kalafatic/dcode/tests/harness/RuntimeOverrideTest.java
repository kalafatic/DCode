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
import eu.kalafatic.dcode.harness.context.ContextEngine;
import eu.kalafatic.dcode.harness.execution.SessionExecutor;
import eu.kalafatic.dcode.harness.orchestration.ToolCallingAgentLoop;
import eu.kalafatic.dcode.harness.session.InMemorySessionService;
import eu.kalafatic.dcode.harness.tool.DefaultToolRegistry;
import eu.kalafatic.dcode.harness.tool.ToolEngine;
import eu.kalafatic.dcode.harness.tools.GitStatusTool;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-task runtime/model override: one session switches runtime for one run, the
 * session selection and the configured default stay untouched. A missing or disabled
 * runtime fails the task with a clear error — never a silent switch to another
 * runtime.
 */
class RuntimeOverrideTest {

    @TempDir
    Path temp;

    private SessionExecutor executor;
    private InMemorySessionService sessionService;
    private Workspace workspace;
    private DefaultProviderRegistry providers;
    private DefaultModelRouter router;

    @BeforeEach
    void setUp() throws Exception {
        Path root = temp.resolve("workspace");
        Files.createDirectories(root);
        Path repoDir = root.resolve("project");
        JGitEngine gitEngine = new JGitEngine();
        gitEngine.init(repoDir);
        Files.writeString(repoDir.resolve("README.md"), "# Project\n");
        gitEngine.commit(repoDir, "seed", "Test", "t@example.org");

        InMemoryEventBus bus = new InMemoryEventBus(null, t -> {});
        InMemoryWorkspaceService workspaceService = new InMemoryWorkspaceService(bus);
        workspace = workspaceService.createWorkspace("override", WorkspaceMode.LOCAL,
                root.toString());
        workspaceService.addRepository(workspace.id(), new RepositoryRef("p1", "project",
                repoDir.toString(), null, "master", RepositoryAccessPolicy.READ_WRITE,
                Instant.now()));

        RepositoryWorkspaceManager workspaceManager = new RepositoryWorkspaceManager(gitEngine);
        RepositoryOperations operations = new RepositoryOperations(gitEngine,
                workspaceManager::repositoryDir);

        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new GitStatusTool());
        toolRegistry.register(new eu.kalafatic.dcode.harness.tools.WriteFileTool());
        ToolEngine toolEngine = new ToolEngine(toolRegistry, bus);

        sessionService = new InMemorySessionService(bus, workspaceService);

        providers = new DefaultProviderRegistry();
        providers.register(new FakeLlmProvider(
                "{\"tool\":\"write_file\",\"arguments\":{\"path\":\"project/marker.txt\","
                        + "\"content\":\"x\"}}",
                "{\"final\":\"from-default-runtime\"}"));
        router = new DefaultModelRouter();
        router.setDefaultModel("fake", "fake-model");

        AgentEngine agentEngine = new AgentEngine(bus);
        ContextEngine contextEngine = new ContextEngine(operations);
        ToolCallingAgentLoop loop = new ToolCallingAgentLoop(providers, toolEngine,
                toolRegistry, contextEngine, sessionService);
        ChangeManager changeManager = new ChangeManager(operations);
        executor = new SessionExecutor(sessionService, workspaceService,
                new InMemoryReviewService(), providers, router, operations, null,
                agentEngine, loop, changeManager, bus);
    }

    @Test
    void perTaskOverrideSwitchesRuntimeForOneRunOnly() throws Exception {
        var session = sessionService.createSession(new SessionService.CreateSessionRequest(
                "override", workspace.id(), "fake", "fake-model", null, null));
        executor.submit(session.getId(), "run one");
        awaitTerminal(session.getId());
        assertTrue(lastAssistantMessage(session.getId()).contains("from-default-runtime"));

        // second runtime: a different fake instance with its own identity
        providers.register(new FakeLlmProviderWithId("fake-alt",
                "{\"final\":\"from-alternate-runtime\"}"));

        // the override run leaves WAITING_FOR_APPROVAL -> RUNNING (resumable session)
        executor.submit(session.getId(), "run two", "fake-alt", "fake-alt-model");
        awaitTerminal(session.getId());
        assertTrue(lastAssistantMessage(session.getId()).contains("from-alternate-runtime"));

        // the session selection itself is untouched
        var stored = sessionService.getSession(session.getId()).orElseThrow();
        assertEquals("fake", stored.getProviderId());
        assertEquals("fake-model", stored.getModelId());
    }

    @Test
    void unknownRuntimeFailsTaskWithoutSilentSwitching() throws Exception {
        var session = sessionService.createSession(new SessionService.CreateSessionRequest(
                "doomed", workspace.id(), "fake", "fake-model", null, null));
        executor.submit(session.getId(), "run", "unknown-runtime", "some-model");
        var finalSession = awaitTerminal(session.getId());
        assertEquals(SessionState.FAILED, finalSession.getState());

        // the configured selection is preserved, not replaced by the failure
        assertEquals("fake", finalSession.getProviderId());
        assertEquals("fake-model", finalSession.getModelId());
    }

    @Test
    void unknownModelOnDiscoveringRuntimeFailsFast() throws Exception {
        var session = sessionService.createSession(new SessionService.CreateSessionRequest(
                "model-miss", workspace.id(), null, null, null, null));
        executor.submit(session.getId(), "run", "fake", "no-such-model");
        var finalSession = awaitTerminal(session.getId());
        assertEquals(SessionState.FAILED, finalSession.getState());
    }

    private eu.kalafatic.dcode.core.model.Session awaitTerminal(String sessionId)
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

    private String lastAssistantMessage(String sessionId) {
        var messages = sessionService.listMessages(sessionId);
        String last = "";
        for (var message : messages) {
            if (message.role() == eu.kalafatic.dcode.core.model.MessageRole.ASSISTANT) {
                last = message.content();
            }
        }
        return last;
    }

    /** Fake provider with a configurable identity (a distinct runtime instance). */
    private static final class FakeLlmProviderWithId extends FakeLlmProvider {
        private final String id;

        FakeLlmProviderWithId(String id, String... responses) {
            super(responses);
            this.id = id;
        }

        @Override
        public String providerId() {
            return id;
        }

        @Override
        public java.util.List<eu.kalafatic.dcode.config.model.ModelDefinition> listModels() {
            return java.util.List.of(new eu.kalafatic.dcode.config.model.ModelDefinition(
                    id + "-model", id + "-model", id, 4096, true));
        }
    }
}
