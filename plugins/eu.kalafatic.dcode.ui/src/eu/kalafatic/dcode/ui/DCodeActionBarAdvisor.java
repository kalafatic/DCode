package eu.kalafatic.dcode.ui;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.swt.SWT;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.actions.ActionFactory.IWorkbenchAction;
import org.eclipse.ui.application.ActionBarAdvisor;
import org.eclipse.ui.application.IActionBarConfigurer;

/**
 * Menu bar: File (connection, session, exit), Edit (standard text actions), Window
 * (perspective + views), Help. Only implemented operations are exposed; accelerators
 * keep the important workflows keyboard-reachable.
 */
public final class DCodeActionBarAdvisor extends ActionBarAdvisor {

    private Action connectAction;
    private Action newSessionAction;
    private IWorkbenchAction exitAction;
    private Action aboutAction;

    public DCodeActionBarAdvisor(IActionBarConfigurer configurer) {
        super(configurer);
    }

    @Override
    protected void makeActions(IWorkbenchWindow window) {
        connectAction = new Action("&Connect to Runtime…") {
            @Override
            public void run() {
                new ConnectionDialog(window.getShell()).open();
            }
        };
        connectAction.setId("eu.kalafatic.dcode.ui.connect");
        connectAction.setToolTipText("Change the DCode server endpoint or bearer token");

        newSessionAction = new Action("&New Session…") {
            @Override
            public void run() {
                showView("eu.kalafatic.dcode.ui.views.sessions");
            }
        };
        newSessionAction.setId("eu.kalafatic.dcode.ui.newSession");
        newSessionAction.setAccelerator(SWT.CTRL | 'N');
        newSessionAction.setToolTipText("Open the Sessions view and create a session");

        exitAction = ActionFactory.QUIT.create(window);
        register(exitAction);

        aboutAction = new Action("About DCode") {
            @Override
            public void run() {
                org.eclipse.jface.dialogs.MessageDialog.openInformation(window.getShell(),
                        "DCode",
                        "DCode — Darwin Code\nNative AI coding harness\n"
                                + "Version 2.6.11");
            }
        };
    }

    @Override
    protected void fillMenuBar(IMenuManager menuBar) {
        MenuManager fileMenu = new MenuManager("&File", "file");
        fileMenu.add(newSessionAction);
        fileMenu.add(connectAction);
        fileMenu.add(new Separator());
        fileMenu.add(exitAction);
        menuBar.add(fileMenu);

        MenuManager editMenu = new MenuManager("&Edit", "edit");
        editMenu.add(ActionFactory.UNDO.create(getActionBarConfigurer().getWindowConfigurer()
                .getWindow()));
        editMenu.add(ActionFactory.REDO.create(getActionBarConfigurer().getWindowConfigurer()
                .getWindow()));
        editMenu.add(new Separator());
        editMenu.add(ActionFactory.CUT.create(getActionBarConfigurer().getWindowConfigurer()
                .getWindow()));
        editMenu.add(ActionFactory.COPY.create(getActionBarConfigurer().getWindowConfigurer()
                .getWindow()));
        editMenu.add(ActionFactory.PASTE.create(getActionBarConfigurer().getWindowConfigurer()
                .getWindow()));
        editMenu.add(new Separator());
        editMenu.add(ActionFactory.SELECT_ALL.create(getActionBarConfigurer()
                .getWindowConfigurer().getWindow()));
        menuBar.add(editMenu);

        MenuManager modelsMenu = new MenuManager("&Models", "models");
        modelsMenu.add(new Action("&Manage Runtimes…") {
            @Override
            public void run() {
                new eu.kalafatic.dcode.ui.views.RuntimeManagerDialog(
                        getActionBarConfigurer().getWindowConfigurer().getWindow()
                                .getShell()).open();
            }
        });
        modelsMenu.add(new Action("&Scan local runtimes…") {
            @Override
            public void run() {
                new eu.kalafatic.dcode.ui.views.ScanRuntimesDialog(
                        getActionBarConfigurer().getWindowConfigurer().getWindow()
                                .getShell()).open();
            }
        });
        modelsMenu.add(new Action("&Refresh models") {
            @Override
            public void run() {
                Ui.refreshAll();
            }
        });
        modelsMenu.add(new Separator());
        modelsMenu.add(showViewAction("Runtime &Models Explorer",
                "eu.kalafatic.dcode.ui.views.runtimeexplorer"));
        modelsMenu.add(showViewAction("Model Explorer (detailed)",
                "eu.kalafatic.dcode.ui.views.models"));
        menuBar.add(modelsMenu);

        MenuManager harnessMenu = new MenuManager("&Harness", "harness");
        harnessMenu.add(showViewAction("AI &Workspace",
                "eu.kalafatic.dcode.ui.views.aiworkspace"));
        harnessMenu.add(new Separator());
        harnessMenu.add(new Action("&Harness status") {
            @Override
            public void run() {
                eu.kalafatic.dcode.ui.Ui.run(RuntimeConnection.instance().api()
                        .harnesses(), harnesses -> {
                            for (com.fasterxml.jackson.databind.JsonNode harness
                                    : harnesses) {
                                eu.kalafatic.dcode.ui.Ui.log("harness",
                                        harness.path("name").asText("") + " — "
                                                + (harness.path("usable").asBoolean()
                                                ? "usable" : "available: "
                                                + harness.path("available").asBoolean()
                                                + ", integrated: "
                                                + harness.path("integrated").asBoolean()));
                            }
                        }, "list harnesses");
            }
        });
        menuBar.add(harnessMenu);

        MenuManager navigateMenu = new MenuManager("&Navigate", "navigate");
        navigateMenu.add(showViewAction("&Project Explorer",
                "eu.kalafatic.dcode.ui.views.workspace"));
        navigateMenu.add(showViewAction("&Runtime & Models",
                "eu.kalafatic.dcode.ui.views.runtimeexplorer"));
        navigateMenu.add(showViewAction("&Agent Explorer",
                "eu.kalafatic.dcode.ui.views.agentexplorer"));
        navigateMenu.add(showViewAction("&Sessions",
                "eu.kalafatic.dcode.ui.views.sessionnavigator"));
        navigateMenu.add(showViewAction("&Connection",
                "eu.kalafatic.dcode.ui.views.connection"));
        menuBar.add(navigateMenu);

        MenuManager searchMenu = new MenuManager("&Search", "search");
        searchMenu.add(new Action("&Models of the selected runtime") {
            @Override
            public void run() {
                showView("eu.kalafatic.dcode.ui.views.runtimeexplorer");
                Ui.log("info", "Model search lives in the Runtime & Models view "
                        + "(bottom section of the left panel).");
            }
        });
        searchMenu.add(new Action("&Sessions…") {
            @Override
            public void run() {
                showView("eu.kalafatic.dcode.ui.views.sessionnavigator");
            }
        });
        menuBar.add(searchMenu);

        MenuManager projectMenu = new MenuManager("&Project", "project");
        projectMenu.add(showViewAction("&Project Explorer",
                "eu.kalafatic.dcode.ui.views.workspace"));
        projectMenu.add(new Separator());
        projectMenu.add(new Action("&Refresh repository state") {
            @Override
            public void run() {
                Ui.refreshAll();
            }
        });
        projectMenu.add(showViewAction("&Changes / diff",
                "eu.kalafatic.dcode.ui.views.changes"));
        menuBar.add(projectMenu);

        MenuManager runMenu = new MenuManager("&Run", "run");
        runMenu.add(new Action("&Submit coding task") {
            @Override
            public void run() {
                showView("eu.kalafatic.dcode.ui.views.aiworkspace");
            }
        });
        runMenu.add(new Action("&Cancel active execution") {
            @Override
            public void run() {
                var session = RuntimeConnection.instance().activeSession();
                if (session == null) {
                    Ui.log("warning", "No active session");
                    return;
                }
                Ui.run(RuntimeConnection.instance().api().cancelSession(session.getId()),
                        json -> Ui.log("task", "Cancellation requested for "
                                + session.getId()), "cancel session");
            }
        });
        runMenu.add(new Separator());
        runMenu.add(new Action("&Run build (session tool)") {
            @Override
            public void run() {
                executeToolOnActiveSession("build",
                        java.util.Map.of());
            }
        });
        runMenu.add(new Action("&Run tests (session tool)") {
            @Override
            public void run() {
                executeToolOnActiveSession("test",
                        java.util.Map.of());
            }
        });
        runMenu.add(showViewAction("&Execution activity",
                "eu.kalafatic.dcode.ui.views.activity"));
        runMenu.add(new Separator());
        runMenu.add(new Action("&Open Web Interface") {
            @Override
            public void run() {
                DesktopActions.openWebInterface();
            }
        });
        runMenu.add(new Action("Open &Terminal in workspace") {
            @Override
            public void run() {
                DesktopActions.openTerminal();
            }
        });
        menuBar.add(runMenu);

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
        windowMenu.add(new Separator());
        windowMenu.add(showViewAction("&Runtime Explorer",
                "eu.kalafatic.dcode.ui.views.runtimes", SWT.CTRL | 'R'));
        windowMenu.add(showViewAction("&Model Explorer",
                "eu.kalafatic.dcode.ui.views.models"));
        windowMenu.add(showViewAction("&Direct Inference Chat",
                "eu.kalafatic.dcode.ui.views.directchat", SWT.CTRL | SWT.ALT | 'C'));
        windowMenu.add(showViewAction("&Console",
                "eu.kalafatic.dcode.ui.views.console"));
        windowMenu.add(showViewAction("Con&nection",
                "eu.kalafatic.dcode.ui.views.connection"));
        windowMenu.add(showViewAction("&Sessions",
                "eu.kalafatic.dcode.ui.views.sessions"));
        windowMenu.add(showViewAction("AI C&hat",
                "eu.kalafatic.dcode.ui.views.chat"));
        windowMenu.add(showViewAction("&Changes",
                "eu.kalafatic.dcode.ui.views.changes"));
        windowMenu.add(showViewAction("&Java Tools",
                "eu.kalafatic.dcode.ui.views.javatools"));
        menuBar.add(windowMenu);

        MenuManager helpMenu = new MenuManager("&Help", "help");
        helpMenu.add(aboutAction);
        menuBar.add(helpMenu);
    }

    private Action showViewAction(String label, String viewId) {
        return showViewAction(label, viewId, 0);
    }

    private Action showViewAction(String label, String viewId, int accelerator) {
        Action action = new Action(label) {
            @Override
            public void run() {
                showView(viewId);
            }
        };
        action.setId("eu.kalafatic.dcode.ui.show." + viewId);
        if (accelerator != 0) {
            action.setAccelerator(accelerator);
        }
        return action;
    }

    private static void showView(String viewId) {
        try {
            org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow()
                    .getActivePage().showView(viewId);
        } catch (Exception e) {
            Ui.log("error", "Cannot open view " + viewId + ": " + e.getMessage());
        }
    }

    /** Executes a session tool (build/test) through the shared backend ToolEngine. */
    private static void executeToolOnActiveSession(String toolName,
                                                   java.util.Map<String, Object> args) {
        var session = RuntimeConnection.instance().activeSession();
        if (session == null) {
            Ui.log("warning", "Open a session first — session tools run in a "
                    + "session context");
            return;
        }
        Ui.log("tool", "Executing '" + toolName + "' on session " + session.getId()
                + "…");
        Ui.run(RuntimeConnection.instance().api().executeTool(toolName,
                        session.getId(), args),
                json -> Ui.log("tool", toolName + " finished: "
                        + json.path("toolCall").path("state").asText()),
                "execute " + toolName);
    }
}
