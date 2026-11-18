package eu.kalafatic.dcode.tests.repositories;

import eu.kalafatic.dcode.core.event.InMemoryEventBus;
import eu.kalafatic.dcode.core.model.RepositoryAccessPolicy;
import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.core.model.WorkspaceMode;
import eu.kalafatic.dcode.repositories.git.JGitEngine;
import eu.kalafatic.dcode.repositories.service.InMemoryWorkspaceService;
import eu.kalafatic.dcode.repositories.service.RepositoryAccessException;
import eu.kalafatic.dcode.repositories.service.RepositoryOperations;
import eu.kalafatic.dcode.repositories.workspace.RepositoryWorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5.3 gate: multi-repository workspace operations and access policy enforcement.
 */
class WorkspaceAndOperationsTest {

    @TempDir
    Path temp;

    private InMemoryWorkspaceService workspaceService;
    private RepositoryOperations operations;
    private RepositoryWorkspaceManager workspaceManager;
    private JGitEngine engine;
    private List<String> events;

    @BeforeEach
    void setUp() {
        engine = new JGitEngine();
        workspaceManager = new RepositoryWorkspaceManager(engine);
        InMemoryEventBus bus = new InMemoryEventBus(null, t -> {});
        events = new CopyOnWriteArrayList<>();
        bus.subscribe(e -> true, e -> events.add(e.type().name()));
        workspaceService = new InMemoryWorkspaceService(bus);
        operations = new RepositoryOperations(engine, workspaceManager::repositoryDir);
    }

    @Test
    void workspaceRegistryOperations() {
        Workspace ws = workspaceService.createWorkspace("family", WorkspaceMode.LOCAL,
                temp.resolve("wsroot").toString());

        assertEquals(0, workspaceService.listRepositories(ws.id()).size());
        assertEquals(1, workspaceService.listWorkspaces().size());

        RepositoryRef evo = repo("evo", temp.resolve("evo-repo"));
        RepositoryRef tryst = repo("tryst", temp.resolve("tryst-repo"));
        ws = workspaceService.addRepository(ws.id(), evo);
        ws = workspaceService.addRepository(ws.id(), tryst);

        assertEquals(2, workspaceService.listRepositories(ws.id()).size());
        assertTrue(events.contains("WORKSPACE_CREATED"));
        assertTrue(events.contains("REPOSITORY_ADDED"));

        final Workspace current = ws;
        assertThrows(IllegalArgumentException.class,
                () -> workspaceService.addRepository(current.id(), repo("evo", temp.resolve("other"))));

        ws = workspaceService.removeRepository(ws.id(), evo.id());
        assertEquals(1, workspaceService.listRepositories(ws.id()).size());
        assertTrue(events.contains("REPOSITORY_REMOVED"));
    }

    @Test
    void crossRepositoryChangeCollection() throws Exception {
        Path root = temp.resolve("wsroot");
        Workspace ws = workspaceService.createWorkspace("cross", WorkspaceMode.LOCAL,
                root.toString());

        RepositoryRef repoA = provisionedRepo(ws, "repoA", root);
        RepositoryRef repoB = provisionedRepo(ws, "repoB", root);
        ws = workspaceService.addRepository(ws.id(), repoA);
        ws = workspaceService.addRepository(ws.id(), repoB);

        Files.writeString(root.resolve("repoA/change-a.txt"), "a\n");
        Files.writeString(root.resolve("repoB/change-b.txt"), "b\n");

        List<String> all = new ArrayList<>();
        for (RepositoryRef r : ws.repositories()) {
            all.addAll(operations.changedFiles(ws, r));
        }
        assertEquals(2, all.size());
        assertTrue(all.contains("change-a.txt"));
        assertTrue(all.contains("change-b.txt"));
    }

    @Test
    void readOnlyRepositoriesRejectMutations() throws Exception {
        Path root = temp.resolve("wsroot2");
        Workspace ws = workspaceService.createWorkspace("policy", WorkspaceMode.LOCAL,
                root.toString());
        RepositoryRef ro = provisionedRepo(ws, "ro-repo", root);
        ws = workspaceService.addRepository(ws.id(), ro);

        // read operations allowed
        operations.info(ws, ro);
        operations.changedFiles(ws, ro);

        // mutating operations rejected
        final Workspace roWorkspace = ws;
        assertThrows(RepositoryAccessException.class,
                () -> operations.commit(roWorkspace, ro, "x", "A", "a@b.c"));
        assertThrows(RepositoryAccessException.class,
                () -> operations.pull(roWorkspace, ro));
        assertThrows(RepositoryAccessException.class,
                () -> operations.push(roWorkspace, ro));
        assertThrows(RepositoryAccessException.class,
                () -> operations.checkoutBranch(roWorkspace, ro, "master"));
    }

    @Test
    void writableRepositoryAllowsCommit() throws Exception {
        Path root = temp.resolve("wsroot3");
        Workspace ws = workspaceService.createWorkspace("rw", WorkspaceMode.LOCAL,
                root.toString());
        RepositoryRef rw = new RepositoryRef("rw1", "rw-repo",
                root.resolve("rw-repo").toString(), null, "master",
                RepositoryAccessPolicy.READ_WRITE, Instant.now());
        engine.init(root.resolve("rw-repo"));
        ws = workspaceService.addRepository(ws.id(), rw);

        Files.writeString(root.resolve("rw-repo/file.txt"), "data\n");
        String hash = operations.commit(ws, rw, "change", "Author", "a@b.c");
        assertTrue(hash != null && !hash.isEmpty());
        assertTrue(operations.info(ws, rw).clean());
    }

    private RepositoryRef provisionedRepo(Workspace ws, String name, Path root) {
        // bare upstream clone target with no remote: init locally instead
        Path dir = root.resolve(name);
        engine.init(dir);
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(dir.toFile())) {
            Files.writeString(dir.resolve("seed.txt"), "seed\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("seed").setAuthor("A", "a@b.c")
                    .setCommitter("A", "a@b.c").call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new RepositoryRef(name, name, dir.toString(), null, "master",
                RepositoryAccessPolicy.READ_ONLY, Instant.now());
    }

    private RepositoryRef repo(String name, Path dir) {
        return new RepositoryRef(name, name, dir.toString(), null, "master",
                RepositoryAccessPolicy.READ_WRITE, Instant.now());
    }
}
