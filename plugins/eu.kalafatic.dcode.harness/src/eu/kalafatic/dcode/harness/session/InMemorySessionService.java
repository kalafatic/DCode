package eu.kalafatic.dcode.harness.session;

import eu.kalafatic.dcode.core.event.DCodeEventType;
import eu.kalafatic.dcode.core.event.EventBus;
import eu.kalafatic.dcode.core.event.StandardEvent;
import eu.kalafatic.dcode.core.model.Message;
import eu.kalafatic.dcode.core.model.MessageRole;
import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.core.model.SessionState;
import eu.kalafatic.dcode.core.service.InvalidStateTransitionException;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.core.service.WorkspaceService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory session service (WP5.5). The SQLite-backed implementation replaces it in
 * WP5.7 without changing the {@link SessionService} contract.
 *
 * <p>State transitions are validated exclusively through the session state machine;
 * persistence is the responsibility of the WP5.7 implementation. Events are published
 * after state changes are applied.</p>
 */
public final class InMemorySessionService implements SessionService {

    private final EventBus eventBus;
    private final java.util.function.Supplier<WorkspaceService> workspaceSupplier;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Message>> messages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    public InMemorySessionService(EventBus eventBus, WorkspaceService workspaceService) {
        this(eventBus, () -> workspaceService);
    }

    public InMemorySessionService(EventBus eventBus,
                                  java.util.function.Supplier<WorkspaceService> workspaceSupplier) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
    }

    @Override
    public Session createSession(CreateSessionRequest request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.workspaceId(), "workspaceId");
        WorkspaceService workspaceService = workspaceSupplier.get();
        if (workspaceService == null || workspaceService.getWorkspace(request.workspaceId()).isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown workspace: " + request.workspaceId());
        }
        String id = UUID.randomUUID().toString();
        Session session = Session.builder()
                .id(id)
                .title(request.title() != null && !request.title().isBlank()
                        ? request.title() : "Untitled session")
                .workspaceId(request.workspaceId())
                .state(SessionState.CREATED)
                .providerId(request.providerId())
                .modelId(request.modelId())
                .agentRole(request.agentRole() != null ? request.agentRole()
                        : eu.kalafatic.dcode.core.model.AgentRole.MAIN)
                .metadata(request.metadata())
                .build();
        sessions.put(id, session);
        messages.put(id, new ArrayList<>());
        sequences.put(id, new AtomicLong());
        eventBus.publish(StandardEvent.of(DCodeEventType.SESSION_CREATED, id,
                "{\"sessionId\":\"" + id + "\"}"));
        return session;
    }

    @Override
    public Optional<Session> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public List<Session> listSessions(String workspaceId) {
        List<Session> result = new ArrayList<>();
        for (Session session : sessions.values()) {
            if (session.getWorkspaceId().equals(workspaceId)) {
                result.add(session);
            }
        }
        result.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        return result;
    }

    @Override
    public Session transition(String sessionId, SessionState target) {
        return sessions.compute(sessionId, (id, current) -> {
            if (current == null) {
                throw new IllegalArgumentException("Unknown session: " + sessionId);
            }
            if (!SessionState.isValidTransition(current.getState(), target)) {
                throw new InvalidStateTransitionException("Invalid session transition "
                        + current.getState() + " -> " + target + " for session " + sessionId);
            }
            Session updated = current.withState(target);
            eventBus.publish(StandardEvent.of(DCodeEventType.SESSION_STATE_CHANGED, sessionId,
                    "{\"from\":\"" + current.getState() + "\",\"to\":\"" + target + "\"}"));
            return updated;
        });
    }

    @Override
    public Message appendMessage(String sessionId, MessageRole role, String content) {
        Objects.requireNonNull(role, "role");
        if (!sessions.containsKey(sessionId)) {
            throw new IllegalArgumentException("Unknown session: " + sessionId);
        }
        List<Message> history = messages.computeIfAbsent(sessionId, k -> new ArrayList<>());
        long sequence = sequences.computeIfAbsent(sessionId, k -> new AtomicLong())
                .incrementAndGet();
        Message message = new Message(UUID.randomUUID().toString(), sessionId, role,
                content, sequence, Instant.now());
        synchronized (history) {
            history.add(message);
        }
        eventBus.publish(StandardEvent.of(DCodeEventType.MESSAGE_APPENDED, sessionId,
                "{\"messageId\":\"" + message.id() + "\",\"role\":\"" + role + "\"}"));
        return message;
    }

    @Override
    public Session renameSession(String sessionId, String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new IllegalArgumentException("Session title must not be blank");
        }
        Session updated = sessions.compute(sessionId, (id, current) -> {
            if (current == null) {
                throw new IllegalArgumentException("Unknown session: " + sessionId);
            }
            return Session.builder()
                    .id(current.getId())
                    .title(newTitle)
                    .workspaceId(current.getWorkspaceId())
                    .state(current.getState())
                    .providerId(current.getProviderId())
                    .modelId(current.getModelId())
                    .agentRole(current.getAgentRole())
                    .metadata(current.getMetadata())
                    .createdAt(current.getCreatedAt())
                    .updatedAt(Instant.now())
                    .build();
        });
        eventBus.publish(StandardEvent.of(DCodeEventType.SESSION_RENAMED, sessionId,
                "{\"title\":\"" + newTitle.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"}"));
        return updated;
    }

    @Override
    public void deleteSession(String sessionId) {
        Session removed = sessions.remove(sessionId);
        if (removed == null) {
            return; // idempotent
        }
        messages.remove(sessionId);
        sequences.remove(sessionId);
        eventBus.publish(StandardEvent.of(DCodeEventType.SESSION_DELETED, sessionId,
                "{\"sessionId\":\"" + sessionId + "\"}"));
    }

    @Override
    public List<Message> listMessages(String sessionId) {
        List<Message> history = messages.get(sessionId);
        if (history == null) {
            return List.of();
        }
        synchronized (history) {
            return List.copyOf(history);
        }
    }
}
