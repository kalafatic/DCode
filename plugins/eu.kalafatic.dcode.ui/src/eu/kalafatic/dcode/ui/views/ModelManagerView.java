package eu.kalafatic.dcode.ui.views;

import com.fasterxml.jackson.databind.JsonNode;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.action.Action;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

import java.util.ArrayList;
import java.util.List;

/**
 * Model Manager: unified provider pull/list/remove with live download
 * progress. All operations run against the shared ENR model management
 * service through the DCode server API — DCode holds no model management
 * state of its own. Progress updates are polled on the SWT UI thread via
 * {@link Display#timerExec} and the poller is cancelled on dispose, so the
 * view never leaks timers.
 */
public final class ModelManagerView extends ViewPart implements Ui.Refreshable {

    private static final int POLL_INTERVAL_MS = 1000;

    private Combo provider;
    private Text modelInput;
    private Button pullButton;
    private Button refreshButton;
    private Label statusLabel;
    private Composite downloadsBox;
    private Table installedTable;
    private final List<JsonNode> providers = new ArrayList<>();
    private final List<JsonNode> jobs = new ArrayList<>();
    private volatile boolean disposed = false;
    private boolean pollScheduled = false;

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(2, false));

        Label providerLabel = new Label(root, SWT.NONE);
        providerLabel.setText("Provider");
        provider = new Combo(root, SWT.READ_ONLY);
        provider.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        provider.addListener(SWT.Selection, event -> refreshInstalled());

        Label modelLabel = new Label(root, SWT.NONE);
        modelLabel.setText("Model");
        modelInput = new Text(root, SWT.BORDER);
        modelInput.setMessage("model identifier, e.g. qwen3-vl:30b");
        modelInput.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        modelInput.addListener(SWT.DefaultSelection, event -> startPull());

        pullButton = new Button(root, SWT.PUSH);
        pullButton.setText("Pull Model");
        refreshButton = new Button(root, SWT.PUSH);
        refreshButton.setText("Refresh");
        pullButton.addListener(SWT.Selection, event -> startPull());
        refreshButton.addListener(SWT.Selection, event -> {
            refreshProviders();
            refreshInstalled();
            refreshDownloads();
        });

        statusLabel = new Label(root, SWT.WRAP);
        statusLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Label downloadsLabel = new Label(root, SWT.NONE);
        downloadsLabel.setText("Active downloads");
        downloadsLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        downloadsBox = new Composite(root, SWT.NONE);
        downloadsBox.setLayout(new GridLayout(1, false));
        downloadsBox.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));

        Label installedLabel = new Label(root, SWT.NONE);
        installedLabel.setText("Installed models");
        installedLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        installedTable = new Table(root, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        installedTable.setHeaderVisible(true);
        installedTable.setLinesVisible(false);
        TableColumn nameColumn = new TableColumn(installedTable, SWT.NONE);
        nameColumn.setText("Model");
        nameColumn.setWidth(340);
        TableColumn sizeColumn = new TableColumn(installedTable, SWT.NONE);
        sizeColumn.setText("Size");
        sizeColumn.setWidth(110);
        TableColumn removeColumn = new TableColumn(installedTable, SWT.NONE);
        removeColumn.setText("");
        removeColumn.setWidth(90);
        GridData tableData = new GridData(GridData.FILL_BOTH);
        tableData.heightHint = 180;
        installedTable.setLayoutData(tableData);
        installedTable.addListener(SWT.MouseDown, event -> {
            TableItem item = installedTable.getItem(
                    installedTable.getDisplay().map(installedTable, null, event.x, event.y));
            if (item == null || !(item.getData() instanceof JsonNode model)) {
                return;
            }
            if (event.x < installedTable.getColumn(0).getWidth()
                    + installedTable.getColumn(1).getWidth()) {
                return;
            }
            removeInstalled(model);
        });

        contributeActions();
        refreshProviders();
        refreshDownloads();
        schedulePoll();
    }

    private void contributeActions() {
        var bars = getViewSite().getActionBars().getToolBarManager();
        bars.add(new Action("Pull") {
            @Override
            public void run() {
                startPull();
            }
        });
        bars.add(new Action("Refresh") {
            @Override
            public void run() {
                refreshProviders();
                refreshInstalled();
                refreshDownloads();
            }
        });
    }

    private void startPull() {
        String providerId = currentProvider();
        String model = modelInput.getText().trim();
        if (providerId.isBlank()) {
            statusLabel.setText("No provider available — is the ENR model management "
                    + "service reachable?");
            return;
        }
        if (model.isBlank()) {
            statusLabel.setText("Enter a model identifier first");
            return;
        }
        statusLabel.setText("Starting pull of " + providerId + "/" + model + "\u2026");
        pullButton.setEnabled(false);
        Ui.run(RuntimeConnection.instance().api().modelPull(providerId, model),
                job -> {
                    String jobId = job.path("jobId").asText(job.path("job_id").asText(""));
                    Ui.log("Pull queued: " + providerId + "/" + model + " (job " + jobId + ")");
                    modelInput.setText("");
                    pullButton.setEnabled(true);
                    refreshDownloads();
                },
                "pull model",
                failure -> {
                    statusLabel.setText("Pull failed: " + failure.getMessage());
                    pullButton.setEnabled(true);
                });
    }

    private void refreshProviders() {
        if (disposed || !RuntimeConnection.instance().isUsableForRequests()) {
            statusLabel.setText("Backend connection " + RuntimeConnection.instance()
                    .state().name() + " — open the Connection view to fix.");
            return;
        }
        Ui.run(RuntimeConnection.instance().api().modelProviders(), data -> {
            synchronized (providers) {
                providers.clear();
                JsonNode arr = data.path("providers");
                if (arr.isArray()) {
                    arr.forEach(entry -> {
                        boolean canPull = entry.path("capabilities").isArray()
                                && contains(entry.path("capabilities"), "MODEL_PULL");
                        if (canPull) {
                            providers.add(entry);
                        }
                    });
                }
            }
            Ui.async(() -> {
                String previous = currentProvider();
                provider.removeAll();
                for (JsonNode entry : providers) {
                    provider.add(entry.path("id").asText("")
                            + " — " + entry.path("display_name").asText(""));
                }
                if (providers.isEmpty()) {
                    statusLabel.setText("No model providers registered in ENR "
                            + "(check enr.yaml model_providers).");
                    return;
                }
                if (!previous.isBlank() && provider.indexOf(previous) >= 0) {
                    provider.select(provider.indexOf(previous));
                } else {
                    provider.select(0);
                }
                refreshInstalled();
            });
        }, "list model providers");
    }

    private String currentProvider() {
        int index = provider.getSelectionIndex();
        synchronized (providers) {
            if (index < 0 || index >= providers.size()) {
                return "";
            }
            return providers.get(index).path("id").asText("");
        }
    }

    private void refreshInstalled() {
        String providerId = currentProvider();
        if (disposed || providerId.isBlank()) {
            return;
        }
        Ui.run(RuntimeConnection.instance().api().modelInstalled(providerId),
                data -> {
                    List<JsonNode> models = new ArrayList<>();
                    JsonNode arr = data.path("models");
                    if (arr.isArray()) {
                        arr.forEach(models::add);
                    }
                    Ui.async(() -> renderInstalled(models));
                },
                "list installed models of " + providerId,
                failure -> statusLabel.setText("Installed models unavailable: "
                        + failure.getMessage()));
    }

    private void removeInstalled(JsonNode model) {
        String providerId = currentProvider();
        String modelId = model.path("id").asText("");
        if (providerId.isBlank() || modelId.isBlank()) {
            return;
        }
        if (!org.eclipse.jface.dialogs.MessageDialog.openConfirm(getViewSite().getShell(),
                "Remove Model", "Remove " + modelId + " from " + providerId + "?")) {
            return;
        }
        Ui.run(RuntimeConnection.instance().api().modelRemove(providerId, modelId),
                json -> {
                    Ui.log("Model removed: " + providerId + "/" + modelId);
                    refreshInstalled();
                }, "remove model " + providerId + "/" + modelId,
                failure -> statusLabel.setText("Remove failed: " + failure.getMessage()));
    }

    private void renderInstalled(List<JsonNode> models) {
        installedTable.removeAll();
        for (JsonNode model : models) {
            TableItem item = new TableItem(installedTable, SWT.NONE);
            item.setText(0, model.path("id").asText(""));
            item.setText(1, formatBytes(model.path("size_bytes").asLong(-1)));
            item.setText(2, "Remove");
            item.setData(model);
        }
    }

    private void refreshDownloads() {
        if (disposed) {
            return;
        }
        Ui.run(RuntimeConnection.instance().api().modelDownloads(),
                data -> {
                    List<JsonNode> active = new ArrayList<>();
                    JsonNode arr = data.path("downloads");
                    if (arr.isArray()) {
                        arr.forEach(job -> {
                            String state = job.path("status").asText("");
                            if (!"COMPLETED".equals(state) && !"FAILED".equals(state)
                                    && !"CANCELLED".equals(state)) {
                                active.add(job);
                            }
                        });
                    }
                    Ui.async(() -> renderDownloads(active));
                },
                "list model downloads",
                failure -> {
                    // ENR unreachable or API mismatch: keep the previous view.
                });
    }

    private void renderDownloads(List<JsonNode> active) {
        synchronized (jobs) {
            jobs.clear();
            jobs.addAll(active);
        }
        for (var child : downloadsBox.getChildren()) {
            child.dispose();
        }
        if (active.isEmpty()) {
            Label empty = new Label(downloadsBox, SWT.NONE);
            empty.setText("No active downloads.");
            downloadsBox.layout();
            return;
        }
        for (JsonNode job : active) {
            Composite row = new Composite(downloadsBox, SWT.NONE);
            row.setLayout(new GridLayout(3, false));
            row.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

            long total = job.path("total_bytes").asLong(-1);
            long completed = job.path("completed_bytes").asLong(0);
            int percent = total > 0
                    ? (int) Math.max(0, Math.min(100, completed * 100 / total)) : 0;
            String state = job.path("status").asText("");
            String operation = job.path("operation").asText("");

            Label label = new Label(row, SWT.WRAP);
            label.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
            label.setText(job.path("provider").asText("") + " / " + job.path("model").asText("")
                    + " — " + state + (operation.isBlank() ? "" : " (" + operation + ")")
                    + (total > 0 ? " " + percent + "% (" + formatBytes(completed)
                    + " / " + formatBytes(total) + ")" : ""));

            ProgressBar bar = new ProgressBar(row, SWT.SMOOTH);
            bar.setMinimum(0);
            bar.setMaximum(total > 0 ? 1000 : 1);
            bar.setSelection(total > 0 ? Math.min(1000, percent * 10) : 0);
            bar.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

            Button cancel = new Button(row, SWT.PUSH);
            cancel.setText("Cancel");
            String jobId = job.path("job_id").asText("");
            cancel.addListener(SWT.Selection, event -> {
                cancel.setEnabled(false);
                Ui.run(RuntimeConnection.instance().api().modelJobCancel(jobId),
                        json -> {
                            Ui.log("Cancel requested for job " + jobId);
                            refreshDownloads();
                        }, "cancel download " + jobId,
                        failure -> {
                            cancel.setEnabled(true);
                            statusLabel.setText("Cancel failed: " + failure.getMessage());
                        });
            });

            if (!job.path("error").asText("").isBlank()) {
                Label error = new Label(row, SWT.WRAP);
                error.setText(job.path("error").asText(""));
                error.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 3, 1));
            }
        }
        downloadsBox.layout();
    }

    private void schedulePoll() {
        if (disposed || pollScheduled) {
            return;
        }
        pollScheduled = true;
        Display.getDefault().timerExec(POLL_INTERVAL_MS, () -> {
            pollScheduled = false;
            if (!disposed) {
                refreshDownloads();
                schedulePoll();
            }
        });
    }

    @Override
    public void refreshContent() {
        refreshProviders();
        refreshInstalled();
        refreshDownloads();
    }

    @Override
    public void setFocus() {
        modelInput.setFocus();
    }

    @Override
    public void dispose() {
        disposed = true;
        super.dispose();
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode entry : array) {
            if (value.equals(entry.asText(""))) {
                return true;
            }
        }
        return false;
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) {
            return "\u2014";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
