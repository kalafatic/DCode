package eu.kalafatic.dcode.core.service;

import eu.kalafatic.dcode.core.model.AgentRole;
import eu.kalafatic.dcode.core.model.Message;
import eu.kalafatic.dcode.core.model.MessageRole;
import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.core.model.SessionState;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Session application service contract.
 *
 * <p>The single authority for session creation, state transitions and message history in
 * DCode. All clients (RCP, Web, HTTP API) use this contract; no client mutates sessions
 * directly. Implementations must validate transitions through the session state machine,
 * persist before publishing events, and serialize concurrent mutations per session.</p>
 */
public interface SessionService {

    Session createSession(CreateSessionRequest request);

    Optional<Session> getSession(String sessionId);

    List<Session> listSessions(String workspaceId);

    /**
     * Performs a validated state transition.
     *
     * @throws InvalidStateTransitionException when the transition is not permitted
     */
    Session transition(String sessionId, SessionState target);

    Message appendMessage(String sessionId, MessageRole role, String content);

    List<Message> listMessages(String sessionId);

    /** Request payload for session creation. */
    record CreateSessionRequest(
            String title,
            String workspaceId,
            String providerId,
            String modelId,
            AgentRole agentRole,
            Map<String, String> metadata
    ) {
        public CreateSessionRequest {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
