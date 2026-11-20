package eu.kalafatic.dcode.core.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Central runtime configuration of a DCode instance. Shared by headless, RCP and web modes;
 * there is deliberately no per-client configuration duplication.
 *
 * @param dataDir       root data directory of this instance (database, artifacts, logs)
 * @param workspacesDir where workspace metadata and repository worktrees live
 * @param artifactsDir  large session artifacts
 * @param logsDir       log files
 * @param httpPort      primary HTTP port (0 = allocate from the default range)
 * @param managementPort management port (0 = allocate)
 * @param bindAddress   bind address (default 127.0.0.1)
 * @param nodeId        stable node identity
 * @param appVersion    application version string
 */
public record CoreConfiguration(
        Path dataDir,
        Path workspacesDir,
        Path artifactsDir,
        Path logsDir,
        int httpPort,
        int managementPort,
        String bindAddress,
        String nodeId,
        String appVersion
) {
    public CoreConfiguration {
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(workspacesDir, "workspacesDir");
        Objects.requireNonNull(artifactsDir, "artifactsDir");
        Objects.requireNonNull(logsDir, "logsDir");
        bindAddress = bindAddress == null ? "127.0.0.1" : bindAddress;
        nodeId = nodeId == null ? "dcode-node-01" : nodeId;
    }

    public static CoreConfiguration defaults(Path homeDir) {
        return new CoreConfiguration(
                homeDir,
                homeDir.resolve("workspaces"),
                homeDir.resolve("artifacts"),
                homeDir.resolve("logs"),
                0,
                0,
                "127.0.0.1",
                "dcode-node-01",
                "2.6.11");
    }
}
