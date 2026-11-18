package eu.kalafatic.dcode.ui;

import org.eclipse.jface.action.Action;
import org.eclipse.ui.actions.ActionFactory.IWorkbenchAction;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.application.ActionBarAdvisor;
import org.eclipse.ui.application.IActionBarConfigurer;

/** Menu bar: File (connect/exit), Edit (standard), Window (perspectives/views), Help. */
public final class DCodeActionBarAdvisor extends ActionBarAdvisor {

    private Action connectAction;
    private IWorkbenchAction exitAction;
    private Action aboutAction;

    public DCodeActionBarAdvisor(IActionBarConfigurer configurer) {
        super(configurer);
    }

    @Override
    protected void makeActions(IWorkbenchWindow window) {
        connectAction = new Action("Connect to Runtime…") {
            @Override
            public void run() {
                new ConnectionDialog(window.getShell()).open();
            }
        };
        connectAction.setId("eu.kalafatic.dcode.ui.connect");

        exitAction = ActionFactory.QUIT.create(window);
        register(exitAction);

        aboutAction = new Action("About DCode") {
            @Override
            public void run() {
                org.eclipse.jface.dialogs.MessageDialog.openInformation(window.getShell(),
                        "DCode",
                        "DCode — Darwin Code\nNative AI coding harness\n"
                                + "Version 0.1.0 (Phase 5)");
            }
        };
    }

    @Override
    protected void fillMenuBar(IMenuManager menuBar) {
        MenuManager fileMenu = new MenuManager("&File", "file");
        fileMenu.add(connectAction);
        fileMenu.add(new Separator());
        fileMenu.add(exitAction);
        menuBar.add(fileMenu);

        MenuManager windowMenu = new MenuManager("&Window", "window");
        windowMenu.add(new Action("Open Perspective: DCode") {
            @Override
            public void run() {
                org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow()
                        .getActivePage().setPerspective(
                                org.eclipse.ui.PlatformUI.getWorkbench()
                                        .getPerspectiveRegistry()
                                        .findPerspectiveWithId(
                                                "eu.kalafatic.dcode.ui.perspective"));
            }
        });
        menuBar.add(windowMenu);

        MenuManager helpMenu = new MenuManager("&Help", "help");
        helpMenu.add(aboutAction);
        menuBar.add(helpMenu);
    }
}
