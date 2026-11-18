package eu.kalafatic.dcode.harness.tool;

import java.util.Map;

/**
 * DCode tool contract: one executable capability exposed to agents.
 *
 * <p>Tools declare metadata and schemas, report errors, support cancellation where
 * possible, and execute through the {@link ToolContext} only. Tool implementation and
 * agent orchestration are strictly separate.</p>
 */
public interface Tool {

    ToolDefinition definition();

    ToolResult execute(ToolContext context, Map<String, Object> arguments);

    default boolean supportsCancellation() {
        return false;
    }

    /** Requests cancellation of a running invocation (best effort). */
    default void cancel() {
    }
}
