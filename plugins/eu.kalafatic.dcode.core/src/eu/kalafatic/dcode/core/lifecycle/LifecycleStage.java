package eu.kalafatic.dcode.core.lifecycle;

/**
 * Ordered lifecycle stages of a DCode runtime. Stages start in declaration order and stop in
 * reverse order. Both headless and RCP runtimes traverse the same stages; the CLIENTS stage
 * differs in content (server+web vs workbench).
 */
public enum LifecycleStage {
    BOOTSTRAP,
    CORE_SERVICES,
    CONFIGURATION,
    PERSISTENCE,
    REPOSITORIES,
    PROVIDERS,
    HARNESS,
    TOOLING,
    SERVER,
    CLIENTS
}
