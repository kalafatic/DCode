package eu.kalafatic.dcode.harness.change;

import eu.kalafatic.dcode.core.model.ChangeSet;
import eu.kalafatic.dcode.core.model.FileChange;
import eu.kalafatic.dcode.core.model.FileChangeStatus;
import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.repositories.service.RepositoryOperations;

import java.util.List;
import java.util.UUID;

/**
 * Change management: collects per-repository change sets after agent execution.
 *
 * <p>Multi-repository work yields one change set per repository with changes; there is
 * no cross-repository transaction abstraction (family rule).</p>
 */
public final class ChangeManager {

    private final RepositoryOperations repositoryOperations;

    public ChangeManager(RepositoryOperations repositoryOperations) {
        this.repositoryOperations = repositoryOperations;
    }

    /** Collects change sets for all session repositories with working-tree changes. */
    public List<ChangeSet> collectChanges(Workspace workspace, String sessionId) {
        java.util.List<ChangeSet> result = new java.util.ArrayList<>();
        for (RepositoryRef repository : workspace.repositories()) {
            List<String> changed = repositoryOperations.changedFiles(workspace, repository);
            if (changed.isEmpty()) {
                continue;
            }
            List<FileChange> files = new java.util.ArrayList<>();
            for (String path : changed) {
                files.add(new FileChange(path, null, FileChangeStatus.MODIFIED, List.of()));
            }
            result.add(new ChangeSet(UUID.randomUUID().toString(), sessionId,
                    repository.id(), "Changes in repository " + repository.name(), files,
                    java.time.Instant.now()));
        }
        return result;
    }
}
