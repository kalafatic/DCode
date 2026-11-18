package eu.kalafatic.dcode.core.model;

import java.time.Instant;
import java.util.Objects;

/** One tool invocation performed by an agent run. */
public record ToolCall(
        String id,
        String sessionId,
        String agentRunId,
        String toolName,
        String argumentsJson,
        String resultJson,
        ExecutionState state,
        String errorMessage,
        Instant startedAt,
        Instant completedAt
) {
    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(state, "state");
    }
}
