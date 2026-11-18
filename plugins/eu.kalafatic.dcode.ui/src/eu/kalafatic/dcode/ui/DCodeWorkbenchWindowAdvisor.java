package eu.kalafatic.dcode.ui;

import org.eclipse.swt.graphics.Point;
import org.eclipse.ui.application.ActionBarAdvisor;
import org.eclipse.ui.application.IActionBarConfigurer;
import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.eclipse.ui.application.WorkbenchWindowAdvisor;

/** Main window configuration: size, title, status line, action bar. */
public final class DCodeWorkbenchWindowAdvisor extends WorkbenchWindowAdvisor {

    public DCodeWorkbenchWindowAdvisor(IWorkbenchWindowConfigurer configurer) {
        super(configurer);
    }

    @Override
    public ActionBarAdvisor createActionBarAdvisor(IActionBarConfigurer configurer) {
        return new DCodeActionBarAdvisor(configurer);
    }

    @Override
    public void preWindowOpen() {
        DCodeWorkbenchProbe.windowAdvisorCalled = true;
        IWorkbenchWindowConfigurer configurer = getWindowConfigurer();
        configurer.setInitialSize(new Point(1440, 900));
        configurer.setShowStatusLine(true);
        configurer.setShowProgressIndicator(true);
        configurer.setTitle("DCode — Darwin Code");
    }
}
