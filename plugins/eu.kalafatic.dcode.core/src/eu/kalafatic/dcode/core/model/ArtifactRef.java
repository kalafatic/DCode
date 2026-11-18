package eu.kalafatic.dcode.core.model;

import java.time.Instant;
import java.util.Objects;

/** Metadata reference to a large artifact stored on the filesystem. */
public record ArtifactRef(
        String id,
        String sessionId,
        String name,
        String type,
        String path,
        long sizeBytes,
        Instant createdAt
) {
    public ArtifactRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(path, "path");
    }
}
