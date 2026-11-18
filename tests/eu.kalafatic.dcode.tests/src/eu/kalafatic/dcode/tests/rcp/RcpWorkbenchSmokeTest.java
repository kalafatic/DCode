package eu.kalafatic.dcode.tests.rcp;

import eu.kalafatic.dcode.ui.DCodeWorkbenchAdvisor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5.10 RCP runtime test: the real DCode workbench (SWT + Eclipse Workbench + the
 * DCode perspective and views) starts against the assembled platform. Requires a
 * display (DISPLAY=:1 on the build machine); skipped cleanly when headless.
 */
class RcpWorkbenchSmokeTest {

    @Test
    void workbenchStartsWithDCodePerspective() throws Exception {
        Assumptions.assumeTrue(System.getenv("DISPLAY") != null
                || !java.awt.GraphicsEnvironment.isHeadless(),
                "no display available; workbench smoke test skipped");

        CountDownLatch started = new CountDownLatch(1);
        final int[] returnCode = {-1};
        final Throwable[] startupFailure = {null};
        Thread workbenchThread = new Thread(() -> {
            Display display = PlatformUI.createDisplay();
            try {
                returnCode[0] = PlatformUI.createAndRunWorkbench(display,
                        new DCodeWorkbenchAdvisor());
            } catch (Throwable t) {
                startupFailure[0] = t;
            } finally {
                started.countDown();
                if (display != null && !display.isDisposed()) {
                    display.dispose();
                }
            }
        }, "dcode-workbench-test");
        workbenchThread.setDaemon(true);
        workbenchThread.start();

        // wait for the workbench to come up; the in-process harness registers the
        // window asynchronously, so we assert on what is deterministic: workbench
        // creation, the advisor flow and the registry contents.
        long deadline = System.currentTimeMillis() + 60000;
        IWorkbench workbench = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                workbench = PlatformUI.getWorkbench();
                if (workbench != null
                        && eu.kalafatic.dcode.ui.DCodeWorkbenchProbe.windowAdvisorCalled) {
                    break;
                }
            } catch (IllegalStateException notYetCreated) {
                // workbench still starting on the worker thread
            }
            Thread.sleep(200);
        }
        assertNotNull(workbench, "workbench must start; startup failure: "
                + (startupFailure[0] != null ? startupFailure[0] : "none"));
        assertTrue(eu.kalafatic.dcode.ui.DCodeWorkbenchProbe.windowAdvisorCalled,
                "the DCode main window advisor must run (window creation started)");

        assertNotNull(workbench.getPerspectiveRegistry()
                        .findPerspectiveWithId("eu.kalafatic.dcode.ui.perspective"),
                "DCode perspective must be registered");
        for (String viewId : new String[]{
                "eu.kalafatic.dcode.ui.views.workspace",
                "eu.kalafatic.dcode.ui.views.sessions",
                "eu.kalafatic.dcode.ui.views.chat",
                "eu.kalafatic.dcode.ui.views.activity",
                "eu.kalafatic.dcode.ui.views.console",
                "eu.kalafatic.dcode.ui.views.javatools",
                "eu.kalafatic.dcode.ui.views.changes",
                "eu.kalafatic.dcode.ui.views.providers",
                "eu.kalafatic.dcode.ui.views.connection"}) {
            assertNotNull(workbench.getViewRegistry().find(viewId),
                    "view must be registered: " + viewId);
        }

        // graceful close
        final IWorkbench startedWorkbench = workbench;
        workbench.getDisplay().syncExec(() -> startedWorkbench.close());
        assertTrue(started.await(30, TimeUnit.SECONDS), "workbench must shut down");
        assertEquals(0, returnCode[0], "workbench must exit cleanly");
    }
}
