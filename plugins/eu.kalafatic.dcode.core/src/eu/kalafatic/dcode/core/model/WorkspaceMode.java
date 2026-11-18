package eu.kalafatic.dcode.core.model;

/** Where a workspace physically lives relative to the accessing client. */
public enum WorkspaceMode {
    /** Workspace on the local machine, accessed by the local runtime. */
    LOCAL,

    /** Workspace on a remote DCode headless server. */
    REMOTE
}
