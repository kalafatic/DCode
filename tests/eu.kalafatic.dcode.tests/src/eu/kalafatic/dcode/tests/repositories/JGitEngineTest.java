package eu.kalafatic.dcode.tests.repositories;

import eu.kalafatic.dcode.repositories.git.GitEngine;
import eu.kalafatic.dcode.repositories.git.JGitEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5.3 gate: JGit-based multi-repository operations verified against real repositories.
 */
class JGitEngineTest {

    @TempDir
    Path temp;

    private GitEngine engine;
    private Path upstream;
    private Path work1;
    private Path work2;

    @BeforeEach
    void setUp() throws Exception {
        engine = new JGitEngine();
        upstream = temp.resolve("upstream.git");
        work1 = temp.resolve("work1");
        work2 = temp.resolve("work2");
        org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(upstream.toFile()).call().close();
    }

    @Test
    void initCommitAndInfo() throws Exception {
        Path repo = temp.resolve("plain");
        engine.init(repo);
        Files.writeString(repo.resolve("hello.txt"), "hello\n");

        var infoBefore = engine.info(repo);
        assertEquals("master", infoBefore.branch());
        assertFalse(infoBefore.clean());
        assertEquals(0, infoBefore.commitCount());

        String hash = engine.commit(repo, "initial", "Test Author", "test@example.org");
        assertNotNull(hash);

        var info = engine.info(repo);
        assertTrue(info.clean());
        assertEquals(1, info.commitCount());
        assertEquals(1, engine.log(repo, 10).size());
        assertEquals(hash, engine.headCommit(repo));
    }

    @Test
    void changedFilesAndDiff() throws Exception {
        Path repo = temp.resolve("diffrepo");
        engine.init(repo);
        Files.writeString(repo.resolve("a.txt"), "one\n");
        engine.commit(repo, "initial", "A", "a@b.c");

        Files.writeString(repo.resolve("a.txt"), "one\ntwo\n");
        Files.writeString(repo.resolve("b.txt"), "new file\n");

        List<String> changed = engine.changedFiles(repo);
        assertEquals(2, changed.size());
        assertTrue(changed.contains("a.txt"));
        assertTrue(changed.contains("b.txt"));

        String diff = engine.diff(repo);
        assertTrue(diff.contains("a.txt"));
        assertTrue(diff.contains("+two"));

        assertTrue(engine.hasChanges(repo));
    }

    @Test
    void clonePushPullAcrossWorktrees() throws Exception {
        engine.clone(upstream.toString(), work1);
        Files.writeString(work1.resolve("f.txt"), "v1\n");
        engine.commit(work1, "add f", "A", "a@b.c");
        engine.push(work1);

        engine.clone(upstream.toString(), work2);
        assertTrue(Files.exists(work2.resolve("f.txt")));

        Files.writeString(work1.resolve("f.txt"), "v2\n");
        engine.commit(work1, "bump", "A", "a@b.c");
        engine.push(work1);

        engine.pull(work2);
        assertEquals("v2\n", Files.readString(work2.resolve("f.txt")));
        assertTrue(engine.hasChanges(work1) == false);
        assertTrue(engine.info(work1).clean());
    }

    @Test
    void branchesAndCheckout() throws Exception {
        engine.clone(upstream.toString(), work1);
        Files.writeString(work1.resolve("x.txt"), "x\n");
        engine.commit(work1, "base", "A", "a@b.c");
        engine.push(work1);

        engine.checkoutBranch(work1, "master");
        assertEquals(List.of("master"), engine.branches(work1));

        // create a second branch via a separate clone push
        Path branchWork = temp.resolve("branchWork");
        engine.clone(upstream.toString(), branchWork);
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(branchWork.toFile())) {
            git.checkout().setCreateBranch(true).setName("feature").call();
            Files.writeString(branchWork.resolve("y.txt"), "y\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("feature work")
                    .setAuthor("A", "a@b.c").setCommitter("A", "a@b.c").call();
            git.push().call();
        }
        engine.pull(work1);
        assertTrue(engine.branches(work1).contains("feature") == false);
        engine.checkoutRemoteBranch(work1, "feature");
        assertTrue(engine.branches(work1).contains("feature"));
        assertTrue(Files.exists(work1.resolve("y.txt")));
    }

    @Test
    void rejectsNonRepository() {
        var e = org.junit.jupiter.api.Assertions.assertThrows(
                eu.kalafatic.dcode.repositories.git.GitException.class,
                () -> engine.info(temp.resolve("not-a-repo")));
        assertTrue(e.getCause() != null && e.getCause().getMessage().contains("Not a Git repository")
                || e.getMessage().contains("Not a Git repository"));
    }
}
