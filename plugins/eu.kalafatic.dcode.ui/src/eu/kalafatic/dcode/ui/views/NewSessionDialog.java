package eu.kalafatic.dcode.ui.views;

import com.fasterxml.jackson.databind.JsonNode;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * New session dialog with per-session runtime/model selection. Leaving the runtime
 * empty keeps the platform default (resolved by the backend router).
 */
public final class NewSessionDialog extends Dialog {

    private Text titleText;
    private Combo runtimeCombo;
    private Combo modelCombo;
    private final List<JsonNode> runtimes = new ArrayList<>();
    private final List<String> modelIds = new ArrayList<>();

    public NewSessionDialog(Shell parent) {
        super(parent);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("New session");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite root = (Composite) super.createDialogArea(parent);
        root.setLayout(new GridLayout(2, false));

        new Label(root, SWT.NONE).setText("Title");
        titleText = new Text(root, SWT.BORDER);
        titleText.setText("Untitled session");
        titleText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        new Label(root, SWT.NONE).setText("Runtime");
        runtimeCombo = new Combo(root, SWT.READ_ONLY);
        runtimeCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        runtimeCombo.add("(platform default)");
        runtimeCombo.addModifyListener(event -> loadModels());

        new Label(root, SWT.NONE).setText("Model");
        modelCombo = new Combo(root, SWT.READ_ONLY);
        modelCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        modelCombo.add("(runtime default)");
        modelCombo.select(0);

        loadRuntimes();
        return root;
    }

    private void loadRuntimes() {
        Ui.run(RuntimeConnection.instance().api().runtimes(), list -> Ui.async(() -> {
            runtimes.clear();
            runtimeCombo.removeAll();
            runtimeCombo.add("(platform default)");
            runtimeCombo.setData("(platform default)", null);
            for (JsonNode runtime : list) {
                if (!runtime.path("enabled").asBoolean()) {
                    continue;
                }
                String label = runtime.path("name").asText(runtime.path("id").asText(""));
                runtimeCombo.add(label);
                runtimeCombo.setData(label, runtime.path("id").asText(""));
                runtimes.add(runtime);
            }
            runtimeCombo.select(0);
        }), "list runtimes");
    }

    private void loadModels() {
        String runtimeId = selectedRuntimeId();
        modelCombo.removeAll();
        modelCombo.add("(runtime default)");
        modelCombo.select(0);
        modelIds.clear();
        if (runtimeId == null) {
            return;
        }
        Ui.run(RuntimeConnection.instance().api().runtimeModels(runtimeId), models ->
                Ui.async(() -> {
                    for (JsonNode model : models) {
                        modelIds.add(model.path("id").asText(""));
                        modelCombo.add(model.path("id").asText(""));
                    }
                    if (modelIds.isEmpty()) {
                        modelCombo.add("(no models published)");
                    }
                    modelCombo.select(0);
                }), "list models of " + runtimeId);
    }

    private String selectedRuntimeId() {
        int index = runtimeCombo.getSelectionIndex();
        if (index <= 0) {
            return null;
        }
        return (String) runtimeCombo.getData(runtimeCombo.getItem(index));
    }

    public String title() {
        return titleText.getText().trim();
    }

    /** Returns the selected runtime id or null for the platform default. */
    public String runtimeId() {
        int index = runtimeCombo.getSelectionIndex();
        if (index <= 0) {
            return null;
        }
        return (String) runtimeCombo.getData(runtimeCombo.getItem(index));
    }

    /** Returns the selected model id or null for the runtime default. */
    public String modelId() {
        int index = modelCombo.getSelectionIndex();
        if (index <= 0 || index - 1 >= modelIds.size()) {
            return null;
        }
        return modelIds.get(index - 1);
    }
}
