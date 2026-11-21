package eu.kalafatic.dcode.ui.views;

import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.action.Action;
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
        hookContextMenu();
        refreshContent();
    }

    private void openSession(Session session) {
        SessionActions.open(session);
    }

    /** Context menu for session items: open, rename, delete, cancel execution. */
    private void hookContextMenu() {
        org.eclipse.jface.action.MenuManager manager =
                new org.eclipse.jface.action.MenuManager();
        manager.setRemoveAllWhenShown(true);
        manager.addMenuListener(menu -> {
            TreeItem[] selection = tree.getSelection();
            if (selection.length == 0 || !(selection[0].getData() instanceof Session session)) {
                return;
            }
            menu.add(new Action("Open") {
                @Override
                public void run() {
                    openSession(session);
                }
            });
            menu.add(new Action("Rename\u2026") {
                @Override
                public void run() {
                    SessionActions.rename(tree.getShell(), session,
                            SessionsView.this::refreshContent);
                }
            });
            menu.add(new Action("Delete") {
                @Override
                public void run() {
                    SessionActions.delete(tree.getShell(), session,
                            SessionsView.this::refreshContent);
                }
            });
            if (session.getState() == eu.kalafatic.dcode.core.model.SessionState.RUNNING) {
                menu.add(new Action("Cancel execution") {
                    @Override
                    public void run() {
                        Ui.run(RuntimeConnection.instance().api().cancelSession(
                                        session.getId()),
                                json -> Ui.log("info", "Cancellation requested for "
                                        + session.getId()),
                                "cancel session");
                    }
                });
            }
        });
        tree.setMenu(manager.createContextMenu(tree));
    }

    private void openNewSessionDialog() {
        Ui.run(RuntimeConnection.instance().api().listWorkspaces(), workspaces -> {
            if (workspaces.isEmpty()) {
                Ui.log("error", "create a workspace first");
                return;
            }
            NewSessionDialog dialog = new NewSessionDialog(tree.getShell());
            if (dialog.open() == org.eclipse.jface.dialogs.Dialog.OK) {
                RuntimeConnection.instance().api().createSession(dialog.title(),
                                workspaces.get(0).id(), dialog.runtimeId(),
                                dialog.modelId(), null)
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
            if (!RuntimeConnection.instance().isUsableForRequests()) {
                TreeItem error = new TreeItem(tree, SWT.NONE);
                error.setText("(backend connection " + RuntimeConnection.instance()
                        .state().name() + " — open the Connection view to fix)");
                return;
            }
            Ui.run(RuntimeConnection.instance().api().listWorkspaces(), workspaces -> {
                if (workspaces.isEmpty()) {
                    TreeItem empty = new TreeItem(tree, SWT.NONE);
                    empty.setText("(no workspaces — create one in the Workspace "
                            + "Explorer)");
                    return;
                }
                for (var workspace : workspaces) {
                    TreeItem workspaceItem = new TreeItem(tree, SWT.NONE);
                    workspaceItem.setText(workspace.name());
                    Ui.run(RuntimeConnection.instance().api().listSessions(workspace.id()),
                            sessions -> {
                                if (sessions.isEmpty()) {
                                    TreeItem empty = new TreeItem(workspaceItem, SWT.NONE);
                                    empty.setText("(no sessions — \"New session…\")");
                                }
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
