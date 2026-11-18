package eu.kalafatic.dcode.ui;

import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.eclipse.ui.application.WorkbenchAdvisor;
import org.eclipse.ui.application.WorkbenchWindowAdvisor;

/** Workbench configuration: default perspective, branding, clean layout defaults. */
public final class DCodeWorkbenchAdvisor extends WorkbenchAdvisor {

    private static final String PERSPECTIVE_ID = "eu.kalafatic.dcode.ui.perspective";

    @Override
    public WorkbenchWindowAdvisor createWorkbenchWindowAdvisor(
            IWorkbenchWindowConfigurer configurer) {
        return new DCodeWorkbenchWindowAdvisor(configurer);
    }

    @Override
    public String getInitialWindowPerspectiveId() {
        return PERSPECTIVE_ID;
    }

    @Override
    public void preStartup() {
        super.preStartup();
        // Initialize the runtime connection: local embedded server when available,
        // otherwise the user-configured remote endpoint.
        RuntimeConnection.instance().initialize();
    }
}
