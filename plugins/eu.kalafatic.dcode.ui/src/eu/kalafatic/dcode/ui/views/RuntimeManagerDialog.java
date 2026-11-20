package eu.kalafatic.dcode.ui.views;

import com.fasterxml.jackson.databind.JsonNode;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime Manager: the guided runtime administration dialog. Basic operations
 * (add, test, enable/disable, set default, remove, scan) are one click each;
 * provider internals stay in the backend and the runtime edit dialog.
 */
public final class RuntimeManagerDialog extends Dialog {

    private Table table;
    private Label status;
    private final List<JsonNode> runtimes = new ArrayList<>();

    public RuntimeManagerDialog(Shell parent) {
        super(parent);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Runtime Manager");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite root = (Composite) super.createDialogArea(parent);
        root.setLayout(new GridLayout(1, false));

        table = new Table(root, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE
                | SWT.V_SCROLL);
        table.setHeaderVisible(true);
        addColumn("Runtime", 180);
        addColumn("Type", 60);
        addColumn("Endpoint", 210);
        addColumn("Enabled", 60);
        addColumn("Default", 60);
        addColumn("Status", 130);
        GridData tableData = new GridData(GridData.FILL_BOTH);
        tableData.widthHint = 720;
        tableData.heightHint = 240;
        table.setLayoutData(tableData);

        status = new Label(root, SWT.WRAP);
        status.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Composite buttons = new Composite(root, SWT.NONE);
        buttons.setLayout(new GridLayout(8, false));
        buttons.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        addButton(buttons, "Add…", () -> {
            new RuntimeDialog(getShell(), null).open();
            refresh();
        });
        addButton(buttons, "Edit…", () -> {
            JsonNode runtime = selected();
            if (runtime != null) {
                new RuntimeDialog(getShell(), runtime).open();
                refresh();
            }
        });
        addButton(buttons, "Test", () -> {
            JsonNode runtime = selected();
            if (runtime != null) {
                String id = runtime.path("id").asText("");
                Ui.run(RuntimeConnection.instance().api().testRuntime(id),
                        result -> Ui.log(result.path("success").asBoolean()
                                ? "success" : "warning", "Runtime " + id + " test: "
                                + result.path("message").asText()), "test runtime");
                refresh();
            }
        });
        addButton(buttons, "Enable/Disable", () -> {
            JsonNode runtime = selected();
            if (runtime != null) {
                String id = runtime.path("id").asText("");
                Ui.run(RuntimeConnection.instance().api().setRuntimeEnabled(id,
                                !runtime.path("enabled").asBoolean()),
                        json -> refresh(), "toggle runtime");
            }
        });
        addButton(buttons, "Set default", () -> {
            JsonNode runtime = selected();
            if (runtime != null) {
                Ui.run(RuntimeConnection.instance().api().setDefaultRuntime(
                                runtime.path("id").asText(""), null),
                        json -> {
                            Ui.log("runtime", "Default runtime: "
                                    + runtime.path("id").asText(""));
                            Ui.refreshAll();
                            refresh();
                        }, "set default runtime");
            }
        });
        addButton(buttons, "Scan local…", () -> {
            new ScanRuntimesDialog(getShell()).open();
            refresh();
        });
        addButton(buttons, "Remove", () -> {
            JsonNode runtime = selected();
            if (runtime == null) {
                return;
            }
            String id = runtime.path("id").asText("");
            boolean confirmed = MessageDialog.openConfirm(getShell(), "Remove runtime",
                    "Remove the registration '" + id + "'?\nModels and data on the "
                            + "external runtime are untouched.");
            if (!confirmed) {
                return;
            }
            Ui.run(RuntimeConnection.instance().api().deleteRuntime(id),
                    json -> {
                        Ui.log("runtime", "Registration " + id + " removed");
                        refresh();
                    }, "remove runtime");
        });
        addButton(buttons, "Refresh", this::refresh);

        refresh();
        return root;
    }

    private void addColumn(String title, int width) {
        TableColumn column = new TableColumn(table, SWT.LEFT);
        column.setText(title);
        column.setWidth(width);
    }

    private void addButton(Composite parent, String label, Runnable action) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(label);
        button.addListener(SWT.Selection, event -> action.run());
    }

    private JsonNode selected() {
        int index = table.getSelectionIndex();
        return index >= 0 && index < runtimes.size() ? runtimes.get(index) : null;
    }

    private void refresh() {
        if (!eu.kalafatic.dcode.ui.RuntimeConnection.instance().isUsableForRequests()) {
            status.setText("Backend not usable (state "
                    + eu.kalafatic.dcode.ui.RuntimeConnection.instance().state().name()
                    + ") — fix the connection, then Refresh.");
            table.removeAll();
            runtimes.clear();
            return;
        }
        status.setText("Loading runtimes…");
        Ui.run(RuntimeConnection.instance().api().runtimes(), list -> Ui.async(() -> {
            runtimes.clear();
            table.removeAll();
            for (JsonNode runtime : list) {
                runtimes.add(runtime);
                TableItem item = new TableItem(table, SWT.NONE);
                item.setText(new String[]{
                        runtime.path("name").asText(runtime.path("id").asText("")),
                        runtime.path("type").asText(""),
                        runtime.path("endpoint").asText(""),
                        runtime.path("enabled").asBoolean() ? "yes" : "no",
                        runtime.path("default").asBoolean() ? "★" : "",
                        statusText(runtime)});
            }
            status.setText(list.size() + " runtime(s) — select one, then use the "
                    + "buttons above. Changes apply to both RCP and Web IDE.");
        }), "list runtimes", this::showLoadFailure);
    }

    /** Failure path of the runtime list: the dialog must never spin forever. */
    private void showLoadFailure(Throwable throwable) {
        Ui.async(() -> {
            if (status == null || status.isDisposed()) {
                return;
            }
            status.setText("Failed to load runtimes: "
                    + (throwable.getMessage() == null
                    ? throwable.getClass().getSimpleName() : throwable.getMessage()));
        });
    }

    private static String statusText(JsonNode runtime) {
        JsonNode status = runtime.path("status");
        if (status.path("reachable").asBoolean()) {
            return "reachable";
        }
        String message = status.path("message").asText("");
        return message.isBlank() ? "untested" : message;
    }
}
