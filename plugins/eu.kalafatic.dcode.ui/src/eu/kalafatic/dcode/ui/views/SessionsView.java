package eu.kalafatic.dcode.ui.views;

import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.part.ViewPart;

/**
 * Sessions view: the same persisted sessions as Web/Headless. Opening a session makes
 * it the active session and starts its SSE stream.
 */
public final class SessionsView extends ViewPart implements Ui.Refreshable {

    private Tree tree;

    @Override
    public void createPartControl(Composite parent) {
        tree = new Tree(parent, SWT.BORDER | SWT.V_SCROLL);
        getViewSite().getActionBars().getToolBarManager().add(new Action("Refresh") {
            @Override
            public void run() {
                refreshContent();
            }
        });
        getViewSite().getActionBars().getToolBarManager().add(new Action("New session…") {
            @Override
            public void run() {
                openNewSessionDialog();
            }
        });
        tree.addListener(SWT.MouseDoubleClick, event -> {
            TreeItem[] selection = tree.getSelection();
            if (selection.length > 0 && selection[0].getData() instanceof Session session) {
                openSession(session);
            }
        });
        refreshContent();
    }

    private void openSession(Session session) {
        RuntimeConnection.instance().setActiveSession(session);
        Ui.connectSessionStream(session);
        Ui.log("Session " + session.getId() + " opened (" + session.getState() + ")");
    }

    private void openNewSessionDialog() {
        Ui.run(RuntimeConnection.instance().api().listWorkspaces(), workspaces -> {
            if (workspaces.isEmpty()) {
                Ui.log("error", "create a workspace first");
                return;
            }
            InputDialog titleDialog = new InputDialog(tree.getShell(), "New session",
                    "Session title:", "Untitled session", null);
            if (titleDialog.open() == org.eclipse.jface.dialogs.Dialog.OK) {
                RuntimeConnection.instance().api().createSession(titleDialog.getValue(),
                                workspaces.get(0).id(), null, null, null)
                        .whenComplete((session, throwable) -> Ui.async(() -> {
                            if (throwable != null) {
                                Ui.log("error", "create session failed: "
                                        + throwable.getMessage());
                                return;
                            }
                            openSession(session);
                            refreshContent();
                        }));
            }
        }, "list workspaces");
    }

    @Override
    public void refreshContent() {
        Ui.async(() -> {
            tree.removeAll();
            Ui.run(RuntimeConnection.instance().api().listWorkspaces(), workspaces -> {
                for (var workspace : workspaces) {
                    TreeItem workspaceItem = new TreeItem(tree, SWT.NONE);
                    workspaceItem.setText(workspace.name());
                    Ui.run(RuntimeConnection.instance().api().listSessions(workspace.id()),
                            sessions -> {
                                for (Session session : sessions) {
                                    TreeItem item = new TreeItem(workspaceItem, SWT.NONE);
                                    item.setText((session.getTitle() + "  ["
                                            + session.getState() + "]"));
                                    item.setData(session);
                                }
                                workspaceItem.setExpanded(true);
                            }, "list sessions");
                }
            }, "list workspaces");
        });
    }

    @Override
    public void setFocus() {
        tree.setFocus();
    }
}
