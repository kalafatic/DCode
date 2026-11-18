package eu.kalafatic.dcode.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A development workspace: a named, persistent group of independent Git repositories that
 * coding sessions may work across.
 */
public record Workspace(
        String id,
        String name,
        WorkspaceMode mode,
        String rootPath,
        List<RepositoryRef> repositories,
        Instant createdAt
) {
    public Workspace {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(createdAt, "createdAt");
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
    }

    public Workspace withRepositories(List<RepositoryRef> updated) {
        return new Workspace(id, name, mode, rootPath, updated, createdAt);
    }
}
