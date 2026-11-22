package eu.kalafatic.dcode.ui;

import eu.kalafatic.dcode.core.server.WebServerControl;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

/**
 * OSGi lookup for the local {@link WebServerControl} service (registered by the
 * server bundle). Returns null when the UI is connected to a remote backend or
 * the server bundle is not active — the Web Interface menu then shows only the
 * operations that are actually available.
 *
 * <p>Any active bundle context sees every service in the framework registry, so the
 * lookup prefers the core bundle's context: it is active in every runtime where the
 * control can possibly exist, including PDE development launches in which the ui
 * bundle itself only runs RESOLVED without a context.</p>
 */
public final class WebServerControlAccess {

    private WebServerControlAccess() {
    }

    public static WebServerControl get() {
        try {
            BundleContext context = contextOf("eu.kalafatic.dcode.core");
            if (context == null) {
                context = contextOf("eu.kalafatic.dcode.ui");
            }
            if (context == null) {
                return null;
            }
            var reference = context.getServiceReference(WebServerControl.class);
            if (reference == null) {
                return null;
            }
            return context.getService(reference);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BundleContext contextOf(String symbolicName) {
        try {
            Bundle bundle = Platform.getBundle(symbolicName);
            return bundle != null ? bundle.getBundleContext() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
