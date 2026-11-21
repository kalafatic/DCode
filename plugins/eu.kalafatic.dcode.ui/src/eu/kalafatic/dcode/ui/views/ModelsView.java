package eu.kalafatic.dcode.ui.views;

import com.fasterxml.jackson.databind.JsonNode;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.action.Action;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.part.ViewPart;

import java.util.ArrayList;
import java.util.List;

/**
 * Model Explorer: unified model browsing across all registered runtimes. Model
 * entries always carry their runtime context; identical model names served by
 * different runtimes remain distinct entries.
 */
public final class ModelsView extends ViewPart implements Ui.Refreshable {

    private Text search;
    private Tree tree;
    private StyledText details;

    private static final class ModelEntry {
        final String runtimeId;
        final String runtimeName;
        final JsonNode model;

        ModelEntry(String runtimeId, String runtimeName, JsonNode model) {
            this.runtimeId = runtimeId;
            this.runtimeName = runtimeName;
            this.model = model;
        }
    }

    private final List<JsonNode> runtimes = new ArrayList<>();
    private final List<ModelEntry> models = new ArrayList<>();

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        search = new Text(root, SWT.BORDER | SWT.SEARCH);
        search.setMessage("Search models\u2026");
        search.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        search.addModifyListener((ModifyListener) event -> render());

        tree = new Tree(root, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        tree.setLayoutData(new GridData(GridData.FILL_BOTH));
        tree.addListener(SWT.Selection, event -> showDetails());

        details = new StyledText(root, SWT.MULTI | SWT.V_SCROLL | SWT.READ_ONLY);
        GridData detailsData = new GridData(GridData.FILL_HORIZONTAL);
        detailsData.heightHint = 120;
        details.setLayoutData(detailsData);

        contributeActions();
        hookContextMenu();
        refreshContent();
    }

    /** Context menu on the model tree: the same operations as the toolbar. */
    private void hookContextMenu() {
        org.eclipse.jface.action.MenuManager manager =
                new org.eclipse.jface.action.MenuManager();
        manager.setRemoveAllWhenShown(true);
        manager.addMenuListener(menu -> {
            menu.add(new Action("Load") {
                @Override
                public void run() {
                    modelAction("load");
                }
            });
            menu.add(new Action("Unload") {
                @Override
                public void run() {
                    modelAction("unload");
                }
            });
            menu.add(new Action("Pull") {
                @Override
                public void run() {
                    modelAction("pull");
                }
            });
            menu.add(new Action("Set as default") {
                @Override
                public void run() {
                    setDefaultModel();
                }
            });
            menu.add(new Action("Refresh") {
                @Override
                public void run() {
                    refreshContent();
                }
            });
        });
        tree.setMenu(manager.createContextMenu(tree));
    }

    private void setDefaultModel() {
        ModelEntry entry = selectedModel();
        if (entry == null) {
            Ui.log("error", "Select a model first");
            return;
        }
        Ui.run(RuntimeConnection.instance().api().setDefaultRuntime(entry.runtimeId,
                        entry.model.path("id").asText("")),
                json -> {
                    Ui.log("info", "Default model set: "
                            + entry.model.path("id").asText("") + " on "
                            + entry.runtimeId);
                    refreshContent();
                }, "set default model");
    }

    private void contributeActions() {
        var bars = getViewSite().getActionBars().getToolBarManager();
        bars.add(new Action("Refresh") {
            @Override
            public void run() {
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
        bars.add(new Action("Load") {
            @Override
            public void run() {
                modelAction("load");
            }
        });
        bars.add(new Action("Unload") {
            @Override
            public void run() {
                modelAction("unload");
            }
        });
        bars.add(new Action("Pull") {
            @Override
            public void run() {
                modelAction("pull");
            }
        });
    }

    private ModelEntry selectedModel() {
        TreeItem[] items = tree.getSelection();
        if (items.length == 0 || !(items[0].getData() instanceof ModelEntry entry)) {
            return null;
        }
        return entry;
    }

    private void modelAction(String action) {
        ModelEntry entry = selectedModel();
        if (entry == null) {
            Ui.log("error", "Select a model first");
            return;
        }
        Ui.log(action + " " + entry.model.path("id").asText("")
                + " on runtime " + entry.runtimeId + "\u2026");
        Ui.run(RuntimeConnection.instance().api().modelAction(
                        entry.runtimeId, entry.model.path("id").asText(""), action),
                json -> {
                    Ui.log("Model " + action + " done: " + json);
                    refreshContent();
                }, "model " + action);
    }

    private void showDetails() {
        ModelEntry entry = selectedModel();
        if (entry == null) {
            details.setText("");
            return;
        }
        JsonNode model = entry.model;
        StringBuilder text = new StringBuilder();
        text.append("Model:    ").append(model.path("id").asText("")).append('\n');
        text.append("Runtime:  ").append(entry.runtimeName).append(" (")
                .append(entry.runtimeId).append(")\n");
        text.append("Context:  ").append(model.path("contextWindowTokens").asLong(0))
                .append(" tokens\n");
        text.append("Size:     ").append(model.path("sizeBytes").asLong(0))
                .append(" bytes\n");
        text.append("Quant:    ").append(model.path("quantization").asText("n/a"))
                .append('\n');
        text.append("Available:").append(model.path("available").asBoolean())
                .append('\n');
        text.append("Loaded:   ").append(model.path("loaded").asBoolean())
                .append('\n');
        text.append("Caps:     ");
        JsonNode capabilities = model.path("capabilities");
        if (capabilities.isArray()) {
            List<String> names = new ArrayList<>();
            capabilities.forEach(c -> names.add(c.asText("")));
            text.append(String.join(", ", names));
        } else {
            text.append("n/a");
        }
        details.setText(text.toString());
    }

    @Override
    public void refreshContent() {
        Ui.async(() -> {
            if (!RuntimeConnection.instance().isUsableForRequests()) {
                tree.removeAll();
                details.setText("Backend connection " + RuntimeConnection.instance()
                        .state().name() + " — open the Connection view to fix.");
                return;
            }
            Ui.run(RuntimeConnection.instance().api().runtimes(), list -> {
                runtimes.clear();
                list.forEach(runtimes::add);
                fetchModels();
            }, "list runtimes");
        });
    }

    private void fetchModels() {
        models.clear();
        java.util.concurrent.atomic.AtomicInteger pending =
                new java.util.concurrent.atomic.AtomicInteger(runtimes.size());
        if (runtimes.isEmpty()) {
            render();
            return;
        }
        for (JsonNode runtime : runtimes) {
            if (!runtime.path("enabled").asBoolean()) {
                if (pending.decrementAndGet() == 0) {
                    render();
                }
                continue;
            }
            String runtimeId = runtime.path("id").asText("");
            String runtimeName = runtime.path("name").asText(runtimeId);
            Ui.run(RuntimeConnection.instance().api().runtimeModels(runtimeId),
                    list -> Ui.async(() -> {
                        synchronized (models) {
                            for (JsonNode model : list) {
                                models.add(new ModelEntry(runtimeId, runtimeName, model));
                            }
                        }
                        if (pending.decrementAndGet() == 0) {
                            render();
                        }
                    }), "list models of " + runtimeId);
        }
    }

    private void render() {
        Ui.async(() -> {
            tree.removeAll();
            String filter = search.getText().trim().toLowerCase();
            for (JsonNode runtime : runtimes) {
                if (!runtime.path("enabled").asBoolean()) {
                    continue;
                }
                String runtimeId = runtime.path("id").asText("");
                String runtimeName = runtime.path("name").asText(runtimeId);
                List<ModelEntry> matching = new ArrayList<>();
                synchronized (models) {
                    for (ModelEntry entry : models) {
                        if (!entry.runtimeId.equals(runtimeId)) {
                            continue;
                        }
                        String id = entry.model.path("id").asText("").toLowerCase();
                        if (!filter.isBlank() && !id.contains(filter)) {
                            continue;
                        }
                        matching.add(entry);
                    }
                }
                TreeItem group = new TreeItem(tree, SWT.NONE);
                group.setText(runtimeName + " (" + runtimeId + ", "
                        + matching.size() + ")");
                group.setData("runtime:" + runtimeId);
                group.setExpanded(!filter.isBlank() || runtimes.size() == 1);
                for (ModelEntry entry : matching) {
                    TreeItem item = new TreeItem(group, SWT.NONE);
                    item.setData(entry);
                    String marker = entry.model.path("loaded").asBoolean() ? " \u25b6" : "";
                    item.setText(entry.model.path("id").asText("") + marker);
                }
            }
        });
    }

    @Override
    public void setFocus() {
        tree.setFocus();
    }
}
