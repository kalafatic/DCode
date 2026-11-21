package eu.kalafatic.dcode.ui.views;

import com.fasterxml.jackson.databind.JsonNode;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.part.ViewPart;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime Explorer: registered inference runtimes with type, endpoint, connection
 * status, health, enabled state and the default indicator. Runtime management actions
 * run on the backend through the HTTP API; nothing here bypasses the platform.
 */
public final class RuntimesView extends ViewPart implements Ui.Refreshable {

    private Tree tree;
    private Label summary;
    private List<JsonNode> runtimes = new ArrayList<>();
    private int autoRefresh = 0;

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        tree = new Tree(root, SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI);
        tree.setHeaderVisible(true);
        tree.setLayoutData(new GridData(GridData.FILL_BOTH));
        addColumn("Runtime", 200);
        addColumn("Type", 130);
        addColumn("Endpoint", 220);
        addColumn("Status", 200);
        addColumn("Enabled", 60);
        addColumn("Default", 60);

        summary = new Label(root, SWT.WRAP);
        summary.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        contributeActions();
        hookContextMenu();

        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseDoubleClick(MouseEvent event) {
                editSelected();
            }
        });

        refreshContent();
    }

    /** Context menu on the runtime table: the same operations as the toolbar. */
    private void hookContextMenu() {
        org.eclipse.jface.action.MenuManager manager =
                new org.eclipse.jface.action.MenuManager();
        manager.setRemoveAllWhenShown(true);
        manager.addMenuListener(menu -> menu.add(new Action("Refresh") {
            @Override
            public void run() {
                refreshContent();
            }
        }));
        tree.setMenu(manager.createContextMenu(tree));
    }

    private void addColumn(String title, int width) {
        TreeColumn column = new TreeColumn(tree, SWT.LEFT);
        column.setText(title);
        column.setWidth(width);
    }

    private void contributeActions() {
        var bars = getViewSite().getActionBars().getToolBarManager();
        bars.add(new Action("Add Runtime\u2026") {
            @Override
            public void run() {
                new RuntimeDialog(getViewSite().getShell(), null).open();
                refreshContent();
            }
        });
        bars.add(new Action("Scan local…") {
            @Override
            public void run() {
                new ScanRuntimesDialog(getViewSite().getShell()).open();
                refreshContent();
            }
        });
        bars.add(new Action("Edit\u2026") {
            @Override
            public void run() {
                editSelected();
            }
        });
        bars.add(new Action("Remove") {
            @Override
            public void run() {
                removeSelected();
            }
        });
        bars.add(new Action("Enable/Disable") {
            @Override
            public void run() {
                toggleEnabled();
            }
        });
        bars.add(new Action("Test Connection") {
            @Override
            public void run() {
                testSelected();
            }
        });
        bars.add(new Action("Set Default") {
            @Override
            public void run() {
                setDefaultSelected();
            }
        });
        bars.add(new Action("Refresh") {
            @Override
            public void run() {
                refreshContent();
            }
        });
    }

    private JsonNode selected() {
        TreeItem[] items = tree.getSelection();
        if (items.length != 1) {
            Ui.log("error", "Select one runtime");
            return null;
        }
        return (JsonNode) items[0].getData();
    }

    private void editSelected() {
        JsonNode runtime = selected();
        if (runtime == null) {
            return;
        }
        new RuntimeDialog(getViewSite().getShell(), runtime).open();
        refreshContent();
    }

    private void removeSelected() {
        JsonNode runtime = selected();
        if (runtime == null) {
            return;
        }
        String id = runtime.path("id").asText("");
        boolean confirmed = MessageDialog.openConfirm(getViewSite().getShell(),
                "Remove Runtime",
                "Remove the runtime registration '" + id + "'?\n\n"
                        + "Only the registration is removed. Models and data on the "
                        + "external runtime are untouched.");
        if (!confirmed) {
            return;
        }
        Ui.run(RuntimeConnection.instance().api().deleteRuntime(id),
                json -> {
                    Ui.log("Runtime registration " + id + " removed (external data untouched)");
                    refreshContent();
                }, "remove runtime");
    }

    private void toggleEnabled() {
        JsonNode runtime = selected();
        if (runtime == null) {
            return;
        }
        String id = runtime.path("id").asText("");
        boolean enabled = runtime.path("enabled").asBoolean();
        Ui.run(RuntimeConnection.instance().api().setRuntimeEnabled(id, !enabled),
                json -> {
                    Ui.log("Runtime " + id + (enabled ? " disabled" : " enabled"));
                    refreshContent();
                }, "toggle runtime");
    }

    private void testSelected() {
        JsonNode runtime = selected();
        if (runtime == null) {
            return;
        }
        String id = runtime.path("id").asText("");
        Ui.log("Testing runtime " + id + "\u2026");
        Ui.run(RuntimeConnection.instance().api().testRuntime(id),
                result -> {
                    Ui.log("runtime " + id + " test: "
                            + (result.path("success").asBoolean() ? "OK" : "FAILED")
                            + " \u2014 " + result.path("message").asText());
                    refreshContent();
                }, "test runtime");
    }

    private void setDefaultSelected() {
        JsonNode runtime = selected();
        if (runtime == null) {
            return;
        }
        String id = runtime.path("id").asText("");
        Ui.run(RuntimeConnection.instance().api().setDefaultRuntime(id, null),
                json -> {
                    Ui.log("Runtime " + id + " is now the default");
                    refreshContent();
                }, "set default runtime");
    }

    @Override
    public void refreshContent() {
        Ui.async(() -> {
            if (!RuntimeConnection.instance().isUsableForRequests()) {
                tree.removeAll();
                summary.setText("Backend connection " + RuntimeConnection.instance()
                        .state().name() + " — open the Connection view to fix. "
                        + "(Toolbar: Refresh retries.)");
                return;
            }
            Ui.run(RuntimeConnection.instance().api().runtimes(), list -> {
                runtimes = new ArrayList<>();
                list.forEach(runtimes::add);
                render();
            }, "list runtimes");
        });
    }

    private void render() {
        Ui.async(() -> {
            tree.removeAll();
            int reachable = 0;
            for (JsonNode runtime : runtimes) {
                TreeItem item = new TreeItem(tree, SWT.NONE);
                item.setData(runtime);
                boolean enabled = runtime.path("enabled").asBoolean();
                boolean def = runtime.path("default").asBoolean();
                JsonNode status = runtime.path("status");
                String statusText = status.path("message").asText("not tested");
                if (status.path("checkedAt").asText("").startsWith("1970")) {
                    statusText = "not tested";
                }
                if (status.path("reachable").asBoolean()) {
                    reachable++;
                }
                item.setText(new String[]{
                        runtime.path("name").asText(runtime.path("id").asText("")),
                        runtime.path("typeDisplayName").asText(
                                runtime.path("type").asText("")),
                        runtime.path("endpoint").asText(""),
                        statusText,
                        enabled ? "yes" : "no",
                        def ? "\u2605" : ""});
            }
            summary.setText(runtimes.size() + " runtime(s), " + reachable
                    + " reachable");
        });
    }

    @Override
    public void setFocus() {
        tree.setFocus();
    }
}
