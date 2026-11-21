package eu.kalafatic.dcode.tests.rcp;

import eu.kalafatic.dcode.ui.DCodeWorkbenchAdvisor;
import eu.kalafatic.dcode.ui.RuntimeConnection;
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

        // Registry reads are lazy-computed on the workbench thread — read them on the
        // display thread to avoid racing the registry computation.
        final IWorkbench registryWorkbench = workbench;
        workbench.getDisplay().syncExec(() -> {
            assertNotNull(registryWorkbench.getPerspectiveRegistry()
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
                    "eu.kalafatic.dcode.ui.views.connection",
                    "eu.kalafatic.dcode.ui.views.inference"}) {
                assertNotNull(registryWorkbench.getViewRegistry().find(viewId),
                        "view must be registered: " + viewId);
            }
        });

        // The inference workspace is part of the default perspective layout: it must
        // instantiate without becoming an error part. The window is created
        // asynchronously on the workbench thread, so poll for it.
        org.eclipse.ui.IWorkbenchWindow window = null;
        long windowDeadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < windowDeadline) {
            if (workbench.getWorkbenchWindows().length > 0) {
                window = workbench.getWorkbenchWindows()[0];
            } else if (workbench.getActiveWorkbenchWindow() != null) {
                window = workbench.getActiveWorkbenchWindow();
            }
            if (window != null && window.getActivePage() != null) {
                break;
            }
            Thread.sleep(200);
        }
        assertNotNull(window, "workbench window must appear");
        final org.eclipse.ui.IWorkbenchWindow finalWindow = window;
        org.eclipse.ui.IWorkbenchPage page = window.getActivePage();
        assertNotNull(page, "active page must exist");
        workbench.getDisplay().syncExec(() -> {
            try {
                page.showView("eu.kalafatic.dcode.ui.views.inference");
            } catch (org.eclipse.ui.PartInitException e) {
                throw new RuntimeException("Inference workspace failed to open: "
                        + e.getMessage(), e);
            }
        });
        workbench.getDisplay().syncExec(() -> {
            org.eclipse.ui.IViewReference reference = finalWindow.getActivePage()
                    .findViewReference("eu.kalafatic.dcode.ui.views.inference");
            assertNotNull(reference, "inference workspace view reference must exist");
            assertNotNull(reference.getView(false),
                    "inference workspace view must instantiate");
        });

        // Web server runtime: the product auto-starts the server bundle. Other
        // tests may have stopped it (order-dependent), so restore the product-runtime
        // state first: start the bundle and trigger its lazy activation. This also
        // makes the web application reachable for the editor's readiness sequence.
        org.osgi.framework.BundleContext bundleContext =
                org.osgi.framework.FrameworkUtil.getBundle(RcpWorkbenchSmokeTest.class)
                        .getBundleContext();
        org.osgi.framework.Bundle serverBundle = null;
        for (org.osgi.framework.Bundle bundle : bundleContext.getBundles()) {
            if ("eu.kalafatic.dcode.server".equals(bundle.getSymbolicName())) {
                serverBundle = bundle;
            }
        }
        assertNotNull(serverBundle, "server bundle must be in the test runtime");
        serverBundle.start();
        serverBundle.loadClass(
                "eu.kalafatic.dcode.server.internal.ServerActivator");
        long webDeadline = System.currentTimeMillis() + 20000;
        while (eu.kalafatic.dcode.ui.WebServerControlAccess.get() == null
                && System.currentTimeMillis() < webDeadline) {
            Thread.sleep(100);
        }
        assertNotNull(eu.kalafatic.dcode.ui.WebServerControlAccess.get(),
                "the local WebServerControl service must be registered in the "
                        + "product runtime (Web Interface start/stop/restart actions)");
        assertEquals(eu.kalafatic.dcode.core.server.WebServerState.RUNNING,
                eu.kalafatic.dcode.ui.WebServerControlAccess.get().state(),
                "the re-activated server runtime must recover the running web "
                        + "server (lifecycle re-activation)");
        assertNotNull(RuntimeConnection.instance().webBaseUrl(),
                "a DCode web surface must resolve in the product runtime "
                        + "(\"Open Web Interface\" must be enabled)");

        // Re-point the runtime connection at the local embedded server (the product
        // self-heals the same way when a configured endpoint is not a DCode backend).
        RuntimeConnection.instance().useLocalServer();
        long readyDeadline = System.currentTimeMillis() + 20000;
        while (RuntimeConnection.instance().state()
                != eu.kalafatic.dcode.client.ConnectionState.READY
                && System.currentTimeMillis() < readyDeadline) {
            Thread.sleep(200);
        }
        assertEquals(eu.kalafatic.dcode.client.ConnectionState.READY,
                RuntimeConnection.instance().state(),
                "the connection must become READY against the local DCode server");

        // File-based diagnostics (tycho-surefire does not capture stdout reliably).
        try {
            var control = eu.kalafatic.dcode.ui.WebServerControlAccess.get();
            StringBuilder diag = new StringBuilder();
            diag.append("property=").append(System.getProperty("dcode.server.port"))
                    .append('\n');
            diag.append("control=").append(control == null ? "null"
                    : control.state() + " port=" + control.port()
                            + " url=" + control.url()).append('\n');
            diag.append("connection=").append(RuntimeConnection.instance().state())
                    .append(" endpoint=").append(RuntimeConnection.instance().endpoint())
                    .append(" identity=").append(RuntimeConnection.instance().identity())
                    .append('\n');
            diag.append("webBaseUrl=").append(RuntimeConnection.instance().webBaseUrl())
                    .append('\n');
            for (org.osgi.framework.Bundle b : bundleContext.getBundles()) {
                if ("eu.kalafatic.dcode.server".equals(b.getSymbolicName())) {
                    diag.append("serverBundleState=").append(b.getState()).append('\n');
                }
            }
            String resolvedBase = RuntimeConnection.instance().webBaseUrl();
            try {
                var http = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(3)).build();
                var response = http.send(java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create(resolvedBase + "/"))
                                .timeout(java.time.Duration.ofSeconds(5)).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                diag.append("httpStatus=").append(response.statusCode()).append('\n');
            } catch (Exception e) {
                diag.append("httpError=").append(e).append('\n');
            }
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("/tmp/opencode/dcode-smoke-diagnostics.txt"),
                    diag.toString());
        } catch (Exception e) {
            // diagnostics are best-effort
        }

        // The harness editor (Web + Settings) must open automatically at startup —
        // the main working area of the workbench — and its SWT Browser must load
        // the real web application.
        final IWorkbench openWorkbench = workbench;
        RcpMultiPageEditorChecks.waitForAutoOpenAndAssert(openWorkbench, finalWindow);

        // graceful close
        final IWorkbench startedWorkbench = workbench;
        workbench.getDisplay().syncExec(() -> startedWorkbench.close());
        assertTrue(started.await(30, TimeUnit.SECONDS), "workbench must shut down");
        assertEquals(0, returnCode[0], "workbench must exit cleanly");
    }
}
