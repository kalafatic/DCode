package eu.kalafatic.dcode.ui;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

/**
 * Layering-safe boot hook for the embedded DCode server runtime.
 *
 * <p>The server bundle ({@code eu.kalafatic.dcode.server}) is lazily activated; the
 * {@link eu.kalafatic.dcode.core.server.WebServerControl} service (which drives the
 * Web Interface menu) only exists once the bundle has been activated. The ui bundle
 * never imports server classes, so activation happens through pure framework APIs:
 * locate the bundle by symbolic name, start it and trigger its lazy activation. Runs
 * on a background thread — the workbench never blocks on the HTTP server.</p>
 */
public final class ServerRuntimeBoot {

    private ServerRuntimeBoot() {
    }

    /**
     * Ensures the embedded server bundle is active so the WebServerControl service is
     * registered before the workbench menus are used. Idempotent and non-blocking.
     */
    public static void ensureServerBundleActive() {
        Thread thread = new Thread(ServerRuntimeBoot::activate, "dcode-server-boot");
        thread.setDaemon(true);
        thread.start();
    }

    private static void activate() {
        int attempts = 20;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                Bundle bundle = FrameworkUtil.getBundle(ServerRuntimeBoot.class);
                if (bundle == null) {
                    return;
                }
                BundleContext context = bundle.getBundleContext();
                if (context == null) {
                    return;
                }
                for (Bundle candidate : context.getBundles()) {
                    if ("eu.kalafatic.dcode.server".equals(candidate.getSymbolicName())
                            && candidate.getState() != Bundle.ACTIVE) {
                        candidate.start();
                        // The bundle declares a lazy activation policy: start() alone
                        // does not run the activator — loading a class triggers it.
                        candidate.loadClass(
                                "eu.kalafatic.dcode.server.internal.ServerActivator");
                    }
                }
                var control = WebServerControlAccess.get();
                if (control != null) {
                    System.out.println("[DCode:boot] embedded DCode server runtime "
                            + "active; web server state=" + control.state()
                            + (control.isRunning() ? " url=" + control.url() : ""));
                    return;
                }
            } catch (Exception e) {
                System.err.println("[DCode:boot] server activation attempt "
                        + attempt + " failed: " + e);
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        System.err.println("[DCode:boot] embedded DCode server runtime did not "
                + "become active after " + attempts + " attempts; Web Interface "
                + "controls will be unavailable.");
    }
}
