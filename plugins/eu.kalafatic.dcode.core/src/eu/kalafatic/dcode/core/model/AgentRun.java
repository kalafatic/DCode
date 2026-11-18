package eu.kalafatic.dcode.core.model;

import java.time.Instant;
import java.util.Objects;

/** One execution of one agent within a session. */
public record AgentRun(
        String id,
        String sessionId,
        AgentRole role,
        String providerId,
        String modelId,
        ExecutionState state,
        String summary,
        Instant startedAt,
        Instant completedAt
) {
    public AgentRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(state, "state");
    }
}
