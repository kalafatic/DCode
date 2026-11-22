package eu.kalafatic.dcode.tests.harness;

import eu.kalafatic.dcode.config.model.ModelDefinition;
import eu.kalafatic.dcode.config.provider.ChatRequest;
import eu.kalafatic.dcode.config.provider.ChatResponse;
import eu.kalafatic.dcode.config.provider.ConnectionTestResult;
import eu.kalafatic.dcode.config.provider.LlmProvider;
import eu.kalafatic.dcode.config.provider.ProviderRegistry;
import eu.kalafatic.dcode.config.routing.DefaultModelRouter;
import eu.kalafatic.dcode.config.runtime.RuntimeCapabilities;
import eu.kalafatic.dcode.config.runtime.RuntimeInfo;
import eu.kalafatic.dcode.config.runtime.RuntimeRegistration;
import eu.kalafatic.dcode.config.runtime.RuntimeRegistry;
import eu.kalafatic.dcode.config.runtime.RuntimeStatus;
import eu.kalafatic.dcode.config.runtime.RuntimeType;
import eu.kalafatic.dcode.config.runtime.StreamHandle;
import eu.kalafatic.dcode.config.runtime.StreamListener;
import eu.kalafatic.dcode.config.runtime.StreamResult;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the unconfigured-model fallback (SessionExecutor.routeFallback):
 * when no model is configured anywhere, a model with a KNOWN context window large
 * enough for the coding-agent protocol must win over toy/unknown-context models
 * published by earlier candidate runtimes. Encodes the live defect where DCode
 * picked the 4-token 'evo' toy model through the ENR Ollama-compatible surface
 * instead of a real coding model from Ollama.
 */
class RuntimeFallbackSelectionTest {

    @TempDir
    Path temp;

    private SessionExecutor executor;
    private InMemorySessionService sessionService;
    private Workspace workspace;
    private FakeRuntimeRegistry runtimes;

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
        workspace = workspaceService.createWorkspace("fallback", WorkspaceMode.LOCAL,
                root.toString());
        workspaceService.addRepository(workspace.id(), new RepositoryRef("p1", "project",
                repoDir.toString(), null, "master", RepositoryAccessPolicy.READ_WRITE,
                Instant.now()));

        RepositoryWorkspaceManager workspaceManager = new RepositoryWorkspaceManager(gitEngine);
        RepositoryOperations operations = new RepositoryOperations(gitEngine,
                workspaceManager::repositoryDir);

        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new GitStatusTool());
        toolRegistry.register(new WriteFileTool());
        ToolEngine toolEngine = new ToolEngine(toolRegistry, bus);

        sessionService = new InMemorySessionService(bus, workspaceService);

        runtimes = new FakeRuntimeRegistry();
        DefaultModelRouter router = new DefaultModelRouter(); // no default configured

        AgentEngine agentEngine = new AgentEngine(bus);
        ContextEngine contextEngine = new ContextEngine(operations);
        ToolCallingAgentLoop loop = new ToolCallingAgentLoop(runtimes, toolEngine,
                toolRegistry, contextEngine, sessionService);
        ChangeManager changeManager = new ChangeManager(operations);
        executor = new SessionExecutor(sessionService, workspaceService,
                new InMemoryReviewService(), runtimes, router, operations, null,
                agentEngine, loop, changeManager, bus);
    }

    @Test
    void fallbackPrefersKnownUsableModelOverUnknownContextToys() throws Exception {
        var session = sessionService.createSession(new SessionService.CreateSessionRequest(
                "unconfigured", workspace.id(), null, null, null, null));
        executor.submit(session.getId(), "perform the task");
        var terminal = awaitTerminal(session.getId());
        assertEquals(SessionState.COMPLETED, terminal.getState());
        assertEquals("real-coder", runtimes.lastStreamedProvider.get(),
                "the fallback must select the runtime with the known usable model");
        assertEquals("real-coder:latest", runtimes.lastStreamedModel.get());
        assertTrue(lastAssistantMessage(session.getId()).contains("picked-real-model"));
    }

    @Test
    void fallbackUsesUnknownContextOnlyWhenNoKnownUsableModelExists() throws Exception {
        runtimes.hideKnownUsable = true;
        var session = sessionService.createSession(new SessionService.CreateSessionRequest(
                "only-toys", workspace.id(), null, null, null, null));
        executor.submit(session.getId(), "perform the task");
        var terminal = awaitTerminal(session.getId());
        assertEquals(SessionState.COMPLETED, terminal.getState());
        assertEquals("unknown-runtime", runtimes.lastStreamedProvider.get());
        assertEquals("u1", runtimes.lastStreamedModel.get());
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
        return sessionService.listMessages(sessionId).stream()
                .filter(m -> m.role() == eu.kalafatic.dcode.core.model.MessageRole.ASSISTANT)
                .reduce((first, second) -> second)
                .map(eu.kalafatic.dcode.core.model.Message::content)
                .orElse("");
    }

    /**
     * Minimal dual ProviderRegistry/RuntimeRegistry: a default toy runtime, an
     * unknown-context runtime and a real coding runtime. Records the provider/model
     * pair that actually streams a completion.
     */
    private static final class FakeRuntimeRegistry implements RuntimeRegistry, ProviderRegistry {

        final AtomicReference<String> lastStreamedProvider = new AtomicReference<>();
        final AtomicReference<String> lastStreamedModel = new AtomicReference<>();
        boolean hideKnownUsable;

        private final List<RuntimeInfo> infos = List.of(
                info("toy", true),
                info("unknown-runtime", false),
                info("real-coder", false));

        private static RuntimeInfo info(String id, boolean defaultRuntime) {
            return new RuntimeInfo(id, id, RuntimeType.ENR, "http://127.0.0.1:1",
                    true, defaultRuntime, true, RuntimeCapabilities.none(),
                    RuntimeStatus.unknown(), Map.of(), -1);
        }

        @Override
        public List<RuntimeInfo> runtimes() {
            return infos;
        }

        @Override
        public Optional<RuntimeInfo> info(String id) {
            return infos.stream().filter(i -> i.id().equals(id)).findFirst();
        }

        @Override
        public Optional<RuntimeInfo> defaultRuntime() {
            return infos.stream().filter(RuntimeInfo::defaultRuntime).findFirst();
        }

        @Override
        public List<ModelDefinition> listModels(String providerId) {
            return switch (providerId) {
                case "toy" -> List.of(
                        model("evo", 4, true),
                        model("tiny-evo", 32, true));
                case "unknown-runtime" -> List.of(
                        model("u1", 0, true),
                        model("u2", 0, true));
                case "real-coder" -> hideKnownUsable
                        ? List.of(model("hidden", 2, true))
                        : List.of(model("real-coder:latest", 262144, true));
                default -> List.of();
            };
        }

        private static ModelDefinition model(String id, long context, boolean available) {
            return new ModelDefinition(id, id, "fake", context, available, 0, "",
                    false, Set.of());
        }

        @Override
        public StreamHandle stream(String providerId, ChatRequest request,
                                   StreamListener listener) {
            lastStreamedProvider.set(providerId);
            lastStreamedModel.set(request.model());
            listener.onToken("picked-real-model");
            listener.onComplete(new StreamResult(
                    new ChatResponse("picked-real-model", "stop", 1, 1), false, null));
            return StreamHandle.noop();
        }

        @Override
        public Optional<String> defaultModel() {
            return Optional.empty();
        }

        @Override
        public boolean isEnabled(String providerId) {
            return infos.stream().anyMatch(i -> i.id().equals(providerId));
        }

        @Override
        public boolean isRegistered(String id) {
            return info(id).isPresent();
        }

        @Override
        public Optional<LlmProvider> find(String providerId) {
            if (info(providerId).isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new LlmProvider() {
                @Override
                public String providerId() {
                    return providerId;
                }

                @Override
                public String displayName() {
                    return providerId;
                }

                @Override
                public String baseUrl() {
                    return "http://127.0.0.1:1";
                }

                @Override
                public boolean isLocal() {
                    return true;
                }

                @Override
                public List<ModelDefinition> listModels() {
                    return FakeRuntimeRegistry.this.listModels(providerId);
                }

                @Override
                public ConnectionTestResult testConnection() {
                    return ConnectionTestResult.error("stub");
                }

                @Override
                public ChatResponse complete(ChatRequest request) {
                    return new ChatResponse("", "stop", 0, 0);
                }
            });
        }

        // Unused contract surface: the executor and agent loop never call these
        // in the fallback flow.

        @Override
        public void register(RuntimeRegistration registration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void register(LlmProvider provider) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unregister(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update(RuntimeRegistration registration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setEnabled(String id, boolean enabled) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setDefault(String id, String defaultModelId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<LlmProvider> providers() {
            return List.of();
        }

        @Override
        public List<ModelDefinition> listAllModels() {
            return List.of();
        }

        @Override
        public ConnectionTestResult testConnection(String providerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatResponse complete(String providerId, ChatRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelDefinition loadModel(String id, String modelId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelDefinition unloadModel(String id, String modelId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelDefinition pullModel(String id, String modelId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RuntimeStatus runtimeStatus(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, String> credentialState(String id) {
            return Map.of();
        }
    }
}
