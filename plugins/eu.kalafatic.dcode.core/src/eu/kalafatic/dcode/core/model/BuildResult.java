package eu.kalafatic.dcode.core.model;

import java.time.Instant;
import java.util.Objects;

/** Result of one build or test execution for a repository. */
public record BuildResult(
        String id,
        String sessionId,
        String repositoryId,
        String kind,
        String command,
        int exitCode,
        boolean success,
        long durationMs,
        String logArtifactId,
        Instant startedAt,
        Instant completedAt
) {
    public BuildResult {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(repositoryId, "repositoryId");
        Objects.requireNonNull(kind, "kind");
    }
}
