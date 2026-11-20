package eu.kalafatic.dcode.ui.views;

import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.core.model.SessionState;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.action.Action;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Sessions: a compact navigator — recent sessions with state, harness/runtime/model
 * summary, open/continue in one double-click. Sessions are secondary navigation;
 * the AI Workspace is the primary area.
 */
public final class SessionNavigatorView extends ViewPart implements Ui.Refreshable,
        Ui.SessionListener {

    private Table table;
    private Text search;
    private Label status;
    private final List<Session> sessions = new ArrayList<>();

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        search = new Text(root, SWT.BORDER | SWT.SEARCH);
        search.setMessage("search sessions…");
        search.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        search.addModifyListener(event -> renderSessions());

        table = new Table(root, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE
                | SWT.V_SCROLL);
        table.setHeaderVisible(true);
        TableColumn titleColumn = new TableColumn(table, SWT.LEFT);
        titleColumn.setText("Session");
        titleColumn.setWidth(140);
        TableColumn stateColumn = new TableColumn(table, SWT.LEFT);
        stateColumn.setText("State");
        stateColumn.setWidth(100);
        table.setLayoutData(new GridData(GridData.FILL_BOTH));

        status = new Label(root, SWT.WRAP);
        status.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        table.addListener(SWT.MouseDoubleClick, event -> openSelected());
        table.addListener(SWT.Selection, event -> showSummary());

        getViewSite().getActionBars().getToolBarManager().add(new Action("New session…") {
            @Override
            public void run() {
                Ui.run(RuntimeConnection.instance().api().listWorkspaces(), workspaces -> {
                    if (workspaces.isEmpty()) {
                        Ui.log("error", "No workspaces exist — create one from the "
                                + "Connection area first");
                        return;
                    }
                    NewSessionDialog dialog = new NewSessionDialog(getSite().getShell());
                    if (dialog.open() == org.eclipse.jface.dialogs.Dialog.OK) {
                        RuntimeConnection.instance().api().createSession(dialog.title(),
                                        workspaces.get(0).id(), dialog.runtimeId(),
                                        dialog.modelId(), null)
                                .whenComplete((session, throwable) -> Ui.async(() -> {
                                    if (throwable != null) {
                                        Ui.reportFailure("create session", throwable);
                                        return;
                                    }
                                    RuntimeConnection.instance().setActiveSession(session);
                                    Ui.connectSessionStream(session);
                                    refreshContent();
                                }));
                    }
                }, "list workspaces");
            }
        });
        getViewSite().getActionBars().getToolBarManager().add(new Action("Refresh") {
            @Override
            public void run() {
                refreshContent();
            }
        });

        Ui.addSessionListener(this);
        refreshContent();
    }

    private void openSelected() {
        int index = table.getSelectionIndex();
        if (index < 0 || index >= sessions.size()) {
            return;
        }
        Session session = sessions.get(index);
        RuntimeConnection.instance().setActiveSession(session);
        Ui.connectSessionStream(session);
        Ui.log("info", "Session " + session.getId() + " active (" + session.getState()
                + ") — continue in the AI Workspace");
    }

    private void showSummary() {
        int index = table.getSelectionIndex();
        if (index < 0 || index >= sessions.size()) {
            status.setText("");
            return;
        }
        Session session = sessions.get(index);
        status.setText(session.getTitle() + " · " + session.getState()
                + (session.getProviderId() != null
                ? " · runtime " + session.getProviderId() : "")
                + (session.getModelId() != null ? " · " + session.getModelId() : ""));
    }

    @Override
    public void refreshContent() {
        Ui.async(() -> {
            if (!RuntimeConnection.instance().isUsableForRequests()) {
                table.removeAll();
                status.setText("Backend connection " + RuntimeConnection.instance()
                        .state().name());
                return;
            }
            status.setText("Loading sessions…");
            sessions.clear();
            Ui.run(RuntimeConnection.instance().api().listWorkspaces(), workspaces -> {
                java.util.concurrent.atomic.AtomicInteger pending =
                        new java.util.concurrent.atomic.AtomicInteger(
                                Math.max(workspaces.size(), 1));
                for (var workspace : workspaces) {
                    Ui.run(RuntimeConnection.instance().api().listSessions(
                                    workspace.id()), list -> Ui.async(() -> {
                                synchronized (sessions) {
                                    sessions.addAll(list);
                                }
                                if (pending.decrementAndGet() == 0) {
                                    renderSessions();
                                }
                            }), "list sessions");
                }
                if (workspaces.isEmpty()) {
                    renderSessions();
                }
            }, "list workspaces");
        });
    }

    private void renderSessions() {
        Ui.async(() -> {
            table.removeAll();
            String query = search.getText().trim().toLowerCase();
            List<Session> matching = new ArrayList<>();
            synchronized (sessions) {
                for (Session session : sessions) {
                    if (!query.isBlank() && !session.getTitle().toLowerCase()
                            .contains(query) && !session.getId().contains(query)) {
                        continue;
                    }
                    matching.add(session);
                }
            }
            matching.sort(Comparator.comparing(Session::getUpdatedAt).reversed());
            if (matching.size() > 40) {
                matching = matching.subList(0, 40);
            }
            Session active = RuntimeConnection.instance().activeSession();
            for (Session session : matching) {
                TableItem item = new TableItem(table, SWT.NONE);
                item.setText(0, (session == active ? "▶ " : "")
                        + session.getTitle());
                item.setText(1, session.getState().name().replace('_', ' '));
                item.setData(session);
            }
            status.setText(matching.size() + " session(s)"
                    + (matching.isEmpty() ? " — New session… starts one" : ""));
        });
    }

    @Override
    public void sessionChanged(Session session) {
        renderSessions();
    }

    @Override
    public void setFocus() {
        search.setFocus();
    }

    @Override
    public void dispose() {
        Ui.removeSessionListener(this);
        super.dispose();
    }
}
