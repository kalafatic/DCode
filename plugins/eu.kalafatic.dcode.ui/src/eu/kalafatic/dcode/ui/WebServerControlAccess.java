package eu.kalafatic.dcode.ui;

import eu.kalafatic.dcode.core.server.WebServerControl;
import org.osgi.framework.FrameworkUtil;

/**
 * OSGi lookup for the local {@link WebServerControl} service (registered by the
 * server bundle). Returns null when the UI is connected to a remote backend or
 * the server bundle is not active — the Web Interface menu then shows only the
 * operations that are actually available.
 */
public final class WebServerControlAccess {

    private WebServerControlAccess() {
    }

    public static WebServerControl get() {
        try {
            var bundle = FrameworkUtil.getBundle(WebServerControlAccess.class);
            if (bundle == null) {
                return null;
            }
            var context = bundle.getBundleContext();
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
}
