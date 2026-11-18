package eu.kalafatic.dcode.server;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

/**
 * DCode headless application: starts the full server runtime without SWT, without a
 * Workbench and without a display (WP5.8 gate). Exit codes: 0 clean stop, 1 error.
 */
public final class HeadlessApplication implements IApplication {

    @Override
    public Object start(IApplicationContext context) {
        System.out.println("[DCode] Headless runtime starting (no UI dependencies)");
        // The server itself is started by the ServerActivator at the SERVER lifecycle
        // stage; this application only waits for stop/shutdown.
        while (true) {
            try {
                synchronized (this) {
                    wait();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println("[DCode] Headless runtime stopped");
        return IApplication.EXIT_OK;
    }

    @Override
    public void stop() {
        synchronized (this) {
            notifyAll();
        }
    }
}
