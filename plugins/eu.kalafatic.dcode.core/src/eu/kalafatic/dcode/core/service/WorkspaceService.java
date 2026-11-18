package eu.kalafatic.dcode.core.service;

import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.core.model.WorkspaceMode;

import java.util.List;
import java.util.Optional;

/**
 * Workspace application service contract.
 *
 * <p>The single authority for workspace and repository registry state. Repository worktree
 * operations (clone/status/diff/commit) are performed by the repository engine in the
 * repositories bundle; this service owns registry metadata and access policy enforcement
 * decisions.</p>
 */
public interface WorkspaceService {

    Workspace createWorkspace(String name, WorkspaceMode mode, String rootPath);

    Optional<Workspace> getWorkspace(String workspaceId);

    List<Workspace> listWorkspaces();

    /**
     * Adds a repository to a workspace.
     *
     * @throws IllegalArgumentException when a repository with the same name already exists
     */
    Workspace addRepository(String workspaceId, RepositoryRef repository);

    Workspace removeRepository(String workspaceId, String repositoryId);

    List<RepositoryRef> listRepositories(String workspaceId);
}
