package eu.kalafatic.dcode.ui.views;

import com.fasterxml.jackson.databind.JsonNode;
import eu.kalafatic.dcode.client.BackendIdentity;
import eu.kalafatic.dcode.client.ConnectionState;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.part.ViewPart;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime &amp; Models: the single navigation panel for inference resources.
 * Runtime instances on top (with live state), the models of the selected runtime
 * below (real provider discovery — nothing is hardcoded). Selecting a model sets the
 * platform default on the backend; double-click actions are explained by tooltips.
 */
public final class RuntimeExplorerView extends ViewPart implements Ui.Refreshable,
        RuntimeConnection.ConnectionStateListener {

    private Label runtimesLabel;
    private Table runtimesTable;
    private Label modelsLabel;
    private Table modelsTable;
    private Label status;
    private final List<JsonNode> runtimes = new ArrayList<>();
    private final List<JsonNode> models = new ArrayList<>();
    private String selectedRuntimeId;

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        runtimesLabel = new Label(root, SWT.NONE);
        runtimesLabel.setText("RUNTIMES");
        runtimesLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        runtimesTable = new Table(root, SWT.BORDER | SWT.FULL_SELECTION
                | SWT.SINGLE | SWT.V_SCROLL);
        runtimesTable.setHeaderVisible(true);
        runtimesTable.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        TableColumn nameColumn = new TableColumn(runtimesTable, SWT.LEFT);
        nameColumn.setText("Runtime");
        nameColumn.setWidth(190);
        TableColumn stateColumn = new TableColumn(runtimesTable, SWT.LEFT);
        stateColumn.setText("State");
        stateColumn.setWidth(80);
        GridData runtimesData = (GridData) runtimesTable.getLayoutData();
        runtimesData.heightHint = 150;

        modelsLabel = new Label(root, SWT.NONE);
        modelsLabel.setText("MODELS");
        modelsLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        modelsTable = new Table(root, SWT.BORDER | SWT.FULL_SELECTION
                | SWT.SINGLE | SWT.V_SCROLL);
        modelsTable.setHeaderVisible(true);
        modelsTable.setLayoutData(new GridData(GridData.FILL_BOTH));
        TableColumn modelColumn = new TableColumn(modelsTable, SWT.LEFT);
        modelColumn.setText("Model");
        modelColumn.setWidth(200);
        TableColumn detailColumn = new TableColumn(modelsTable, SWT.LEFT);
        detailColumn.setText("Size / detail");
        detailColumn.setWidth(110);

        status = new Label(root, SWT.WRAP);
        status.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        contributeActions();
        hookRuntimeMenu();
        runtimesTable.addListener(SWT.Selection, event -> {
            JsonNode runtime = selectedRuntime();
            if (runtime != null) {
                selectedRuntimeId = runtime.path("id").asText("");
                loadModels();
            }
        });
        modelsTable.addListener(SWT.MouseDoubleClick, event -> selectModel());
        RuntimeConnection.instance().addStateListener(this);
        refreshContent();
    }

    private void contributeActions() {
        var bars = getViewSite().getActionBars().getToolBarManager();
        bars.add(new Action("Add…") {
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
        bars.add(new Action("Manage…") {
            @Override
            public void run() {
                new RuntimeManagerDialog(getViewSite().getShell()).open();
                refreshContent();
            }
        });
        bars.add(new Action("Refresh") {
            @Override
            public void run() {
                refreshContent();
            }
        });
    }

    private void hookRuntimeMenu() {
        MenuManager manager = new MenuManager();
        manager.setRemoveAllWhenShown(true);
        manager.addMenuListener(new IMenuListener() {
            @Override
            public void menuAboutToShow(IMenuManager menu) {
                JsonNode runtime = selectedRuntime();
                if (runtime == null) {
                    return;
                }
                String id = runtime.path("id").asText("");
                menu.add(new Action("Test connection") {
                    @Override
                    public void run() {
                        Ui.run(RuntimeConnection.instance().api().testRuntime(id),
                                result -> Ui.log(result.path("success").asBoolean()
                                        ? "success" : "warning", "Runtime " + id
                                        + ": " + result.path("message").asText()),
                                "test runtime");
                        refreshContent();
                    }
                });
                menu.add(new Action("Refresh models") {
                    @Override
                    public void run() {
                        loadModels();
                    }
                });
                menu.add(new Action(runtime.path("enabled").asBoolean()
                        ? "Disable" : "Enable") {
                    @Override
                    public void run() {
                        Ui.run(RuntimeConnection.instance().api().setRuntimeEnabled(id,
                                        !runtime.path("enabled").asBoolean()),
                                json -> refreshContent(),
                                "toggle runtime");
                    }
                });
                menu.add(new Action("Set as default") {
                    @Override
                    public void run() {
                        Ui.run(RuntimeConnection.instance().api()
                                        .setDefaultRuntime(id, null),
                                json -> {
                                    Ui.log("runtime", id + " is now the default");
                                    Ui.refreshAll();
                                }, "set default runtime");
                    }
                });
                menu.add(new Action("Edit…") {
                    @Override
                    public void run() {
                        new RuntimeDialog(getViewSite().getShell(), runtime).open();
                        refreshContent();
                    }
                });
                menu.add(new Action("Remove") {
                    @Override
                    public void run() {
                        boolean confirmed = org.eclipse.jface.dialogs.MessageDialog
                                .openConfirm(getViewSite().getShell(), "Remove runtime",
                                        "Remove the registration '" + id + "'?\n"
                                                + "Models and data on the external "
                                                + "runtime are untouched.");
                        if (!confirmed) {
                            return;
                        }
                        Ui.run(RuntimeConnection.instance().api().deleteRuntime(id),
                                json -> {
                                    Ui.log("runtime", "Registration " + id
                                            + " removed (external data untouched)");
                                    refreshContent();
                                }, "remove runtime");
                    }
                });
            }
        });
        Menu menu = manager.createContextMenu(runtimesTable);
        runtimesTable.setMenu(menu);
    }

    private JsonNode selectedRuntime() {
        int index = runtimesTable.getSelectionIndex();
        if (index < 0 || index >= runtimes.size()) {
            return null;
        }
        return runtimes.get(index);
    }

    private void selectModel() {
        int index = modelsTable.getSelectionIndex();
        if (index < 0 || index >= models.size() || selectedRuntimeId == null) {
            return;
        }
        JsonNode model = models.get(index);
        String modelId = model.path("id").asText("");
        Ui.run(RuntimeConnection.instance().api().setDefaultRuntime(selectedRuntimeId,
                        modelId),
                json -> {
                    Ui.log("runtime", "Default inference: " + selectedRuntimeId
                            + " / " + modelId);
                    Ui.refreshAll();
                }, "set default model");
    }

    @Override
    public void refreshContent() {
        Ui.async(() -> {
            if (!RuntimeConnection.instance().isUsableForRequests()) {
                runtimesTable.removeAll();
                modelsTable.removeAll();
                status.setText("Backend connection " + RuntimeConnection.instance()
                        .state().name() + " — see the Connection view or Scan…");
                return;
            }
            Ui.run(RuntimeConnection.instance().api().runtimes(), list -> Ui.async(() -> {
                runtimes.clear();
                runtimesTable.removeAll();
                int selected = runtimesTable.getSelectionIndex();
                int defaultIndex = 0;
                for (int i = 0; i < list.size(); i++) {
                    JsonNode runtime = list.get(i);
                    runtimes.add(runtime);
                    TableItem item = new TableItem(runtimesTable, SWT.NONE);
                    String name = runtime.path("name").asText(
                            runtime.path("id").asText(""));
                    String type = runtime.path("type").asText("");
                    String mark = runtime.path("default").asBoolean() ? " ★" : "";
                    boolean enabled = runtime.path("enabled").asBoolean();
                    item.setText(0, name + mark + (enabled ? "" : " (disabled)"));
                    JsonNode runtimeStatus = runtime.path("status");
                    String stateText = runtimeStatus.path("reachable").asBoolean()
                            ? "● reachable"
                            : (runtimeStatus.path("message").asText("").isBlank()
                            ? "○ untested" : "○ "
                            + runtimeStatus.path("message").asText(""));
                    item.setText(1, stateText);
                    item.setData(runtime);
                    if (runtime.path("default").asBoolean()) {
                        defaultIndex = i;
                    }
                }
                if (list.size() == 0) {
                    status.setText("No runtimes — use Add… or Scan local…");
                    modelsTable.removeAll();
                    return;
                }
                runtimesTable.select(selected >= 0 && selected < list.size()
                        ? selected : defaultIndex);
                selectedRuntimeId = runtimes.get(runtimesTable.getSelectionIndex())
                        .path("id").asText("");
                modelsLabel.setText("MODELS — "
                        + runtimes.get(runtimesTable.getSelectionIndex())
                        .path("name").asText(""));
                loadModels();
            }), "list runtimes");
        });
    }

    private void loadModels() {
        if (selectedRuntimeId == null) {
            return;
        }
        modelsTable.removeAll();
        status.setText("Loading models…");
        Ui.run(RuntimeConnection.instance().api().runtimeModels(selectedRuntimeId),
                list -> Ui.async(() -> {
                    models.clear();
                    modelsTable.removeAll();
                    for (JsonNode model : list) {
                        models.add(model);
                        TableItem item = new TableItem(modelsTable, SWT.NONE);
                        String name = model.path("id").asText("");
                        item.setText(0, (model.path("loaded").asBoolean() ? "▸ " : "")
                                + name);
                        long size = model.path("sizeBytes").asLong(0);
                        String quantization = model.path("quantization").asText("");
                        item.setText(1, size > 0 ? humanSize(size)
                                + (quantization.isBlank() ? "" : " · " + quantization)
                                : "");
                        item.setData(model);
                    }
                    status.setText(models.size() + " model(s) · double-click a model "
                            + "to use it");
                }), "list models of " + selectedRuntimeId);
    }

    private static String humanSize(long bytes) {
        if (bytes > 1e9) {
            return String.format("%.1f GB", bytes / 1e9);
        }
        if (bytes > 1e6) {
            return String.format("%.1f MB", bytes / 1e6);
        }
        if (bytes > 1e3) {
            return String.format("%.1f KB", bytes / 1e3);
        }
        return bytes + " B";
    }

    @Override
    public void stateChanged(ConnectionState state, BackendIdentity identity,
                             String diagnostic) {
        if (state == ConnectionState.READY) {
            refreshContent();
        }
    }

    @Override
    public void setFocus() {
        runtimesTable.setFocus();
    }

    @Override
    public void dispose() {
        RuntimeConnection.instance().removeStateListener(this);
        super.dispose();
    }
}
