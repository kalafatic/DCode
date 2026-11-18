package eu.kalafatic.dcode.harness.execution;

import eu.kalafatic.dcode.config.git.GitAccountManager;
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
        if (running.containsKey(sessionId)) {
            throw new IllegalStateException("Session already executing: " + sessionId);
        }
        SessionService sessionService = sessionSupplier.get();
        Session session = sessionService.transition(sessionId, SessionState.RUNNING);
        sessionService.appendMessage(sessionId, MessageRole.USER, task);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancellations.put(sessionId, cancelled);
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
            String providerId = session.getProviderId() != null ? session.getProviderId()
                    : selection.providerId();
            String modelId = session.getModelId() != null ? session.getModelId()
                    : selection.modelId();

            var run = agentEngine.startRun(sessionId,
                    session.getAgentRole() != null ? session.getAgentRole() : AgentRole.MAIN,
                    providerId, modelId);

            Map<String, Object> services = new HashMap<>();
            services.put("repositoryOperations", repositoryOperations);
            if (gitAccounts != null) {
                services.put("gitAccounts", gitAccounts);
            }
            services.put("sessionService", sessionService);
            var outcome = agentLoop.run(workspace, session, task, providerId, modelId,
                    services, cancelled::get);

            if (cancelled.get()) {
                agentEngine.finishRun(run.id(), ExecutionState.CANCELLED, outcome.summary());
                sessionService.transition(sessionId, SessionState.CANCELLED);
                return;
            }
            if (!outcome.success()) {
                agentEngine.finishRun(run.id(), ExecutionState.FAILED, outcome.summary());
                sessionService.transition(sessionId, SessionState.FAILED);
                eventBus.publish(StandardEvent.of(DCodeEventType.SYSTEM_ERROR, sessionId,
                        "{\"message\":\"Execution failed: " + escape(outcome.summary()) + "\"}"));
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
            } catch (Exception transitionError) {
                DCodeLog.error("execution", "Failed to mark session " + sessionId
                        + " FAILED", transitionError);
            }
        } finally {
            running.remove(sessionId);
            cancellations.remove(sessionId);
        }
    }

    private ModelSelection resolveModel(Session session) {
        if (session.getProviderId() != null && !session.getProviderId().isBlank()
                && session.getModelId() != null && !session.getModelId().isBlank()) {
            return new ModelSelection(session.getProviderId(), session.getModelId());
        }
        AgentRole role = session.getAgentRole() != null ? session.getAgentRole()
                : AgentRole.MAIN;
        return modelRouter.resolve(role);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
