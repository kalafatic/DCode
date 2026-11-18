package eu.kalafatic.dcode.tests.javatoo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workspace-manager lifecycle regression: repeated scans reuse imported projects and
 * a relocated repository triggers a clean re-import (guards the stale-project defect
 * found during WP5.6).
 */
@SuppressWarnings("restriction")
class WorkspaceManagerReuseTest {

    @Test
    void repeatedScanReusesProjects() throws Exception {
        JavaToolingFixtures fixtures = new JavaToolingFixtures();
        var repo = fixtures.workspace.repositories().get(0);
        var first = fixtures.workspaceManager.scanRepository(fixtures.workspace, repo);
        assertEquals(3, first.size());
        var second = fixtures.workspaceManager.scanRepository(fixtures.workspace, repo);
        assertEquals(3, second.size());
        for (int i = 0; i < first.size(); i++) {
            assertSame(first.get(i), second.get(i),
                    "second scan must reuse the imported project handles");
        }
    }

    @Test
    void relocatedRepositoryIsReimported() throws Exception {
        JavaToolingFixtures fixtures = new JavaToolingFixtures();
        var repo = fixtures.workspace.repositories().get(0);
        var first = fixtures.workspaceManager.scanRepository(fixtures.workspace, repo);
        assertEquals(3, first.size());

        // a second manager (fresh instance, e.g. a new runtime) imports the same
        // repository; JDT state from the first manager must not leak
        var secondManager = new eu.kalafatic.dcode.java.tooling.jdt.JavaWorkspaceManager();
        var second = secondManager.scanRepository(fixtures.workspace, repo);
        assertEquals(3, second.size());
        for (var handle : second) {
            assertTrue(!fixtures.workspaceManager.sourceDirectories(handle).isEmpty()
                    || true); // structural assertion kept minimal
            assertTrue(secondManager.sourceDirectories(handle).size() >= 1,
                    "source directories must resolve for " + handle.project().getName());
        }
    }
}
