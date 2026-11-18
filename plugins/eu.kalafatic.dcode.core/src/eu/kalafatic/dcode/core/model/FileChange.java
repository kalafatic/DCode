package eu.kalafatic.dcode.core.model;

import java.util.List;
import java.util.Objects;

/**
 * One changed file of a change set.
 *
 * @param path repository-relative path
 * @param oldPath original path for renames, otherwise null
 */
public record FileChange(
        String path,
        String oldPath,
        FileChangeStatus status,
        List<DiffHunk> hunks
) {
    public FileChange {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(status, "status");
        hunks = hunks == null ? List.of() : List.copyOf(hunks);
    }
}
