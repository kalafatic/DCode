package eu.kalafatic.dcode.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A reviewable set of changes produced by a session, scoped to one repository.
 * Multi-repository work produces one change set per repository; there is deliberately no
 * cross-repository atomic transaction abstraction.
 */
public record ChangeSet(
        String id,
        String sessionId,
        String repositoryId,
        String summary,
        List<FileChange> files,
        Instant createdAt
) {
    public ChangeSet {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(repositoryId, "repositoryId");
        files = files == null ? List.of() : List.copyOf(files);
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }
}
