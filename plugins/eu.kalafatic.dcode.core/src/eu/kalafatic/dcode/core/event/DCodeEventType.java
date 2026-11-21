package eu.kalafatic.dcode.core.event;

/** Typed event types published on the DCode event bus. */
public enum DCodeEventType {

    // Session lifecycle
    SESSION_CREATED,
    SESSION_STATE_CHANGED,
    SESSION_RENAMED,
    SESSION_DELETED,
    MESSAGE_APPENDED,

    /** Incremental streaming tokens of an in-progress assistant message (dataJson:
     *  {"content":"<delta>"}). The definitive message arrives via MESSAGE_APPENDED. */
    MESSAGE_PARTIAL,

    // Execution
    AGENT_RUN_STARTED,
    AGENT_RUN_COMPLETED,
    TOOL_CALL_STARTED,
    TOOL_CALL_COMPLETED,
    EXECUTION_PROGRESS,

    // Workspace / repositories
    WORKSPACE_CREATED,
    REPOSITORY_ADDED,
    REPOSITORY_REMOVED,
    REPOSITORY_CHANGED,

    // Build / test
    BUILD_COMPLETED,

    // System
    SYSTEM_INFO,
    SYSTEM_ERROR
}
