package eu.kalafatic.dcode.core.model;

import java.time.Instant;
import java.util.Objects;

/** A repository participating in a DCode workspace. */
public record RepositoryRef(
        String id,
        String name,
        String localPath,
        String remoteUrl,
        String branch,
        RepositoryAccessPolicy accessPolicy,
        Instant addedAt
) {
    public RepositoryRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(localPath, "localPath");
        Objects.requireNonNull(accessPolicy, "accessPolicy");
        if (branch == null) {
            branch = "";
        }
    }

    public boolean isReadOnly() {
        return accessPolicy == RepositoryAccessPolicy.READ_ONLY;
    }

    public boolean isWritable() {
        return accessPolicy == RepositoryAccessPolicy.READ_WRITE;
    }
}
