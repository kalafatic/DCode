package eu.kalafatic.dcode.harness.execution;

import eu.kalafatic.dcode.config.git.GitAccountManager;
import eu.kalafatic.dcode.config.model.ModelDefinition;
import eu.kalafatic.dcode.config.provider.ProviderException;
import eu.kalafatic.dcode.config.provider.ProviderRegistry;
import eu.kalafatic.dcode.config.routing.ModelRouter;
import eu.kalafatic.dcode.config.routing.ModelSelection;
import eu.kalafatic.dcode.core.event.DCodeEventType;
import eu.kalafatic.dcode.core.event.EventBus;
import eu.kalafatic.dcode.core.event.StandardEvent;
import eu.kalafatic.dcode.core.log.DCodeLog;
import eu.kalafatic.dcode.core.model.AgentRole;
import eu.kalafatic.dcode.core.model.ExecutionState;
import eu.kalafatic.dcode.core.model.MessageRole;
import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.core.model.SessionState;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.core.service.WorkspaceService;
import eu.kalafatic.dcode.harness.agent.AgentEngine;
import eu.kalafatic.dcode.harness.change.ChangeManager;
import eu.kalafatic.dcode.core.service.ReviewService;
import eu.kalafatic.dcode.harness.orchestration.ToolCallingAgentLoop;
import eu.kalafatic.dcode.repositories.service.RepositoryOperations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Session execution engine: submits agent runs for sessions, tracks runtime execution
 * state separately from persisted session state, and enforces cancellation and timeouts.
 *
 * <p>Dependencies are resolved per submission through suppliers so that the durable
 * (SQLite) services are used whenever present, regardless of bundle start order.
 * Long-running work never blocks caller threads.</p>
 *
 * <p>Inference runtime selection follows the explicit chain: per-task override, then
 * per-session selection, then the router default. A missing or disabled runtime fails
 * the task with a clear error — never a silent switch to another runtime.</p>
 */
public final class SessionExecutor {

    private final Supplier<SessionService> sessionSupplier;
    private final Supplier<WorkspaceService> workspaceSupplier;
    private final Supplier<ReviewService> reviewSupplier;
    private final ProviderRegistry providerRegistry;
    private final ModelRouter modelRouter;
    private final RepositoryOperations repositoryOperations;
    private final GitAccountManager gitAccounts;
    private final AgentEngine agentEngine;
    private final ToolCallingAgentLoop agentLoop;
    private final ChangeManager changeManager;
    private final EventBus eventBus;

    private final ExecutorService executor =
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "dcode-session-executor");
                t.setDaemon(true);
                return t;
            });
    private final ConcurrentHashMap<String, Future<?>> running = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> cancellations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ModelSelection> taskOverrides = new ConcurrentHashMap<>();

    public SessionExecutor(Supplier<SessionService> sessionSupplier,
                           Supplier<WorkspaceService> workspaceSupplier,
                           Supplier<ReviewService> reviewSupplier,
                           ProviderRegistry providerRegistry, ModelRouter modelRouter,
                           RepositoryOperations repositoryOperations,
                           GitAccountManager gitAccounts, AgentEngine agentEngine,
                           ToolCallingAgentLoop agentLoop, ChangeManager changeManager,
                           EventBus eventBus) {
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.reviewSupplier = Objects.requireNonNull(reviewSupplier, "reviewSupplier");
        this.providerRegistry = Objects.requireNonNull(providerRegistry, "providerRegistry");
        this.modelRouter = Objects.requireNonNull(modelRouter, "modelRouter");
        this.repositoryOperations = Objects.requireNonNull(repositoryOperations,
                "repositoryOperations");
        this.gitAccounts = gitAccounts;
        this.agentEngine = Objects.requireNonNull(agentEngine, "agentEngine");
        this.agentLoop = Objects.requireNonNull(agentLoop, "agentLoop");
        this.changeManager = Objects.requireNonNull(changeManager, "changeManager");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

    /** Convenience constructor for direct (non-OSGi) wiring. */
    public SessionExecutor(SessionService sessionService, WorkspaceService workspaceService,
                           ReviewService reviewService, ProviderRegistry providerRegistry,
                           ModelRouter modelRouter, RepositoryOperations repositoryOperations,
                           GitAccountManager gitAccounts, AgentEngine agentEngine,
                           ToolCallingAgentLoop agentLoop, ChangeManager changeManager,
                           EventBus eventBus) {
        this(() -> sessionService, () -> workspaceService, () -> reviewService,
                providerRegistry, modelRouter, repositoryOperations, gitAccounts, agentEngine,
                agentLoop, changeManager, eventBus);
    }

    /** Submits a task for asynchronous execution in a session. */
    public synchronized void submit(String sessionId, String task) {
        submit(sessionId, task, null, null);
    }

    /**
     * Submits a task with an optional per-task runtime/model override. The override is
     * validated and used for this run only; the session selection and the configured
     * default remain unchanged.
     */
    public synchronized void submit(String sessionId, String task,
                                    String providerIdOverride, String modelIdOverride) {
        if (running.containsKey(sessionId)) {
            throw new IllegalStateException("Session already executing: " + sessionId);
        }
        SessionService sessionService = sessionSupplier.get();
        Session session = sessionService.transition(sessionId, SessionState.RUNNING);
        sessionService.appendMessage(sessionId, MessageRole.USER, task);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancellations.put(sessionId, cancelled);
        if (providerIdOverride != null && !providerIdOverride.isBlank()
                || modelIdOverride != null && !modelIdOverride.isBlank()) {
            taskOverrides.put(sessionId, new ModelSelection(providerIdOverride,
                    modelIdOverride));
        }
        Future<?> future = executor.submit(() -> execute(session, task, cancelled));
        running.put(sessionId, future);
    }

    /** Requests cancellation of a running session. Idempotent. */
    public void cancel(String sessionId) {
        AtomicBoolean cancelled = cancellations.get(sessionId);
        if (cancelled != null) {
            cancelled.set(true);
        }
    }

    /** True while a session has runtime execution work. */
    public boolean isExecuting(String sessionId) {
        Future<?> future = running.get(sessionId);
        return future != null && !future.isDone();
    }

    private void execute(Session session, String task, AtomicBoolean cancelled) {
        String sessionId = session.getId();
        SessionService sessionService = sessionSupplier.get();
        WorkspaceService workspaceService = workspaceSupplier.get();
        ReviewService reviewService = reviewSupplier.get();
        DCodeLog.info("execution", "Session " + sessionId + " execution started");
        try {
            Workspace workspace = workspaceService.getWorkspace(session.getWorkspaceId())
                    .orElseThrow(() -> new IllegalStateException("Workspace disappeared: "
                            + session.getWorkspaceId()));

            ModelSelection selection = resolveModel(session);
            validateRuntime(selection, sessionId);

            var run = agentEngine.startRun(sessionId,
                    session.getAgentRole() != null ? session.getAgentRole() : AgentRole.MAIN,
                    selection.providerId(), selection.modelId());            Map<String, Object> services = new HashMap<>();
            services.put("repositoryOperations", repositoryOperations);
            if (gitAccounts != null) {
                services.put("gitAccounts", gitAccounts);
            }
            services.put("sessionService", sessionService);
            var outcome = agentLoop.run(workspace, session, task, selection.providerId(),
                    selection.modelId(), services, cancelled::get);

            if (cancelled.get()) {
                agentEngine.finishRun(run.id(), ExecutionState.CANCELLED, outcome.summary());
                sessionService.transition(sessionId, SessionState.CANCELLED);
                return;
            }
            if (!outcome.success()) {
                agentEngine.finishRun(run.id(), ExecutionState.FAILED, outcome.summary());
                sessionService.appendMessage(sessionId, MessageRole.ASSISTANT,
                        "[FAILED] " + outcome.summary());
                sessionService.transition(sessionId, SessionState.FAILED);
                eventBus.publish(StandardEvent.of(DCodeEventType.SYSTEM_ERROR, sessionId,
                        "{\"message\":\"Execution failed (runtime="
                                + escape(selection.providerId()) + ", model="
                                + escape(selection.modelId()) + "): "
                                + escape(outcome.summary()) + "\"}"));
                return;
            }

            var changeSets = changeManager.collectChanges(workspace, sessionId);
            for (var changeSet : changeSets) {
                reviewService.createReview(sessionId, changeSet.id());
            }
            agentEngine.finishRun(run.id(), ExecutionState.COMPLETED, outcome.summary());
            sessionService.appendMessage(sessionId, MessageRole.ASSISTANT,
                    "Completed: " + outcome.summary());
            SessionState next = changeSets.isEmpty() ? SessionState.COMPLETED
                    : SessionState.WAITING_FOR_APPROVAL;
            sessionService.transition(sessionId, next);
            DCodeLog.info("execution", "Session " + sessionId + " finished -> " + next
                    + " (" + outcome.toolCalls() + " tool calls, " + outcome.iterations()
                    + " iterations)");
        } catch (Exception e) {
            DCodeLog.error("execution", "Session " + sessionId + " execution error", e);
            try {
                agentEngine.runsOf(sessionId).stream()
                        .filter(r -> r.state() == ExecutionState.RUNNING)
                        .forEach(r -> agentEngine.finishRun(r.id(), ExecutionState.FAILED,
                                e.getMessage()));
                sessionService.transition(sessionId, SessionState.FAILED);
                eventBus.publish(StandardEvent.of(DCodeEventType.SYSTEM_ERROR, sessionId,
                        "{\"message\":\"Execution failed: " + escape(e.getMessage())
                                + "\"}"));
            } catch (Exception transitionError) {
                DCodeLog.error("execution", "Failed to mark session " + sessionId
                        + " FAILED", transitionError);
            }
        } finally {
            running.remove(sessionId);
            cancellations.remove(sessionId);
            taskOverrides.remove(sessionId);
        }
    }

    /**
     * Resolves the effective provider/model pair: per-task override first, then the
     * session selection, then the router default. A partial selection (provider without
     * model) keeps the provider and falls back to the router model, preserving the
     * historical selection semantics. The router is consulted only when needed.
     */
    private ModelSelection resolveModel(Session session) {
        ModelSelection override = taskOverrides.get(session.getId());
        String sessionProvider = session.getProviderId();
        String sessionModel = session.getModelId();
        if (override != null && notBlank(override.providerId())) {
            String model = notBlank(override.modelId()) ? override.modelId()
                    : notBlank(sessionModel) ? sessionModel
                    : routeFallback(session).modelId();
            return new ModelSelection(override.providerId(), model);
        }
        if (notBlank(sessionProvider)) {
            String model = notBlank(sessionModel) ? sessionModel
                    : routeFallback(session).modelId();
            return new ModelSelection(sessionProvider, model);
        }
        return routeFallback(session);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private ModelSelection routeFallback(Session session) {
        AgentRole role = session.getAgentRole() != null ? session.getAgentRole()
                : AgentRole.MAIN;
        try {
            return modelRouter.resolve(role);
        } catch (ProviderException routerError) {
            // No explicit model configuration anywhere: resolve through the runtime
            // registry — the default runtime first, then the other enabled runtimes.
            // Models with an unknown or large enough context window are preferred so
            // the coding-agent protocol can actually run; tiny toy models are only
            // chosen when nothing better is registered.
            if (providerRegistry instanceof eu.kalafatic.dcode.config.runtime.RuntimeRegistry runtimes) {
                List<eu.kalafatic.dcode.config.runtime.RuntimeInfo> candidates =
                        new java.util.ArrayList<>();
                runtimes.defaultRuntime().ifPresent(candidates::add);
                runtimes.runtimes().stream()
                        .filter(eu.kalafatic.dcode.config.runtime.RuntimeInfo::enabled)
                        .filter(runtime -> candidates.stream().noneMatch(
                                existing -> existing.id().equals(runtime.id())))
                        .forEach(candidates::add);
                for (eu.kalafatic.dcode.config.runtime.RuntimeInfo runtime : candidates) {
                    List<ModelDefinition> models;
                    try {
                        models = runtimes.listModels(runtime.id());
                    } catch (RuntimeException e) {
                        continue; // discovery failed on this runtime: try the next
                    }
                    String registeredDefault = candidates.get(0).id().equals(runtime.id())
                            ? runtimes.defaultModel().orElse("") : "";
                    ModelSelection selection = pickModel(runtime.id(), models,
                            registeredDefault);
                    if (selection != null) {
                        DCodeLog.info("execution", "No configured model; using "
                                + selection.modelId() + " on runtime "
                                + runtime.id());
                        return selection;
                    }
                }
            }
            throw routerError;
        }
    }

    /**
     * Picks a model for an unconfigured execution: the registered runtime default
     * model, then the first available model with an unknown or >= 4096 token context
     * window, then any available model.
     */
    private static ModelSelection pickModel(String providerId, List<ModelDefinition> models,
                                            String registeredDefault) {
        if (!registeredDefault.isBlank()) {
            boolean defaultPresent = models.stream()
                    .anyMatch(model -> model.id().equals(registeredDefault));
            if (defaultPresent) {
                return new ModelSelection(providerId, registeredDefault);
            }
        }
        java.util.Optional<ModelDefinition> usable = models.stream()
                .filter(ModelDefinition::available)
                .filter(model -> model.contextWindowTokens() <= 0
                        || model.contextWindowTokens() >= 4096)
                .findFirst();
        if (usable.isPresent()) {
            return new ModelSelection(providerId, usable.get().id());
        }
        return models.stream()
                .filter(ModelDefinition::available)
                .findFirst()
                .map(model -> new ModelSelection(providerId, model.id()))
                .orElse(null);
    }

    /**
     * Pre-flight validation of the selected runtime/model. Fails fast with a clear
     * message; the selection is never silently replaced by another runtime.
     */
    private void validateRuntime(ModelSelection selection, String sessionId) {
        String providerId = selection.providerId();
        String modelId = selection.modelId();
        if (!providerRegistry.find(providerId).isPresent()) {
            throw new ProviderException("Runtime '" + providerId + "' is not registered");
        }
        if (!providerRegistry.isEnabled(providerId)) {
            throw new ProviderException("Runtime '" + providerId + "' is disabled; "
                    + "enable it or select another runtime");
        }
        List<ModelDefinition> models;
        try {
            models = providerRegistry.listModels(providerId);
        } catch (ProviderException e) {
            // Model discovery unsupported or runtime unreachable: the completion call
            // reports the definitive error.
            DCodeLog.warn("execution", "Model discovery on runtime '" + providerId
                    + "' failed: " + e.getMessage());
            return;
        }
        if (modelId == null || modelId.isBlank()) {
            return;
        }
        if (models.isEmpty()) {
            return;
        }
        boolean known = models.stream().anyMatch(m -> m.id().equals(modelId));
        if (!known) {
            throw new ProviderException("Model '" + modelId + "' is not available on "
                    + "runtime '" + providerId + "' (discovered: "
                    + models.stream().limit(5).map(ModelDefinition::id)
                    .collect(java.util.stream.Collectors.joining(", ")) + ")");
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
