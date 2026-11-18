package eu.kalafatic.dcode.harness.tool;

/** Tool execution scope. */
public enum ToolScope {
    /** Operates on files inside one repository working directory. */
    REPOSITORY,

    /** Operates across the workspace (multiple repositories). */
    WORKSPACE,

    /** Runtime-level operation (no workspace semantics). */
    SYSTEM
}
