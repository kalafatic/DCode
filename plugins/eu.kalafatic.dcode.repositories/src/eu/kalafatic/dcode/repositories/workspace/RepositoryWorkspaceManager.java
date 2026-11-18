package eu.kalafatic.dcode.repositories.workspace;

import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.repositories.git.GitEngine;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Resolves and provisions repository working directories inside a workspace root.
 *
 * <p>Each repository gets a dedicated directory named after the repository; the workspace
 * root is the container of all repositories of that workspace. Provisioning clones missing
 * repositories; existing worktrees are reused, never silently reset.</p>
 */
public final class RepositoryWorkspaceManager {

    private final GitEngine engine;

    public RepositoryWorkspaceManager(GitEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /** Returns the working directory of a repository within a workspace. */
    public Path repositoryDir(Workspace workspace, RepositoryRef repository) {
        return Path.of(workspace.rootPath(), repository.name());
    }

    /**
     * Ensures the repository working directory exists: an existing git worktree is reused,
     * an empty directory is treated as a clone target, a non-empty non-git directory is an
     * error.
     */
    public Path provision(Workspace workspace, RepositoryRef repository) {
        Path dir = repositoryDir(workspace, repository);
        if (Files.isDirectory(dir.resolve(".git"))) {
            return dir;
        }
        if (Files.exists(dir)) {
            if (isDirectoryEmpty(dir)) {
                engine.clone(repository.remoteUrl(), dir);
                return dir;
            }
            throw new IllegalStateException("Workspace directory exists but is not a Git "
                    + "repository: " + dir);
        }
        engine.clone(repository.remoteUrl(), dir);
        return dir;
    }

    private static boolean isDirectoryEmpty(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            return !stream.iterator().hasNext();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot inspect directory " + dir, e);
        }
    }
}
