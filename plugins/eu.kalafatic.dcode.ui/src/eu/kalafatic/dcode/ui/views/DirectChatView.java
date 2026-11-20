package eu.kalafatic.dcode.ui.views;

import com.fasterxml.jackson.databind.JsonNode;
import eu.kalafatic.dcode.client.DCodeApiClient;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.action.Action;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Direct Inference Chat: standalone chat with a selected runtime/model that does not
 * require a DCode coding task. Distinct from the agentic coding harness: ordinary
 * prompts never route through the coding harness here.
 */
public final class DirectChatView extends ViewPart implements Ui.Refreshable {

    private Combo runtimeCombo;
    private Combo modelCombo;
    private Text input;
    private Button send;
    private Button cancel;
    private StyledText transcript;
    private final List<JsonNode> runtimes = new ArrayList<>();
    private final List<String> modelIds = new ArrayList<>();
    private final List<Map<String, String>> history = new ArrayList<>();
    private volatile DCodeApiClient.InferenceStreamSession activeStream;

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(4, false));

        new Label(root, SWT.NONE).setText("Runtime");
        runtimeCombo = new Combo(root, SWT.READ_ONLY);
        runtimeCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        runtimeCombo.addModifyListener(event -> loadModels());

        new Label(root, SWT.NONE).setText("Model");
        modelCombo = new Combo(root, SWT.READ_ONLY);
        modelCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        transcript = new StyledText(root, SWT.MULTI | SWT.V_SCROLL | SWT.READ_ONLY
                | SWT.WRAP);
        GridData transcriptData = new GridData(GridData.FILL_BOTH);
        transcriptData.horizontalSpan = 4;
        transcript.setLayoutData(transcriptData);

        input = new Text(root, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.WRAP);
        GridData inputData = new GridData(GridData.FILL_HORIZONTAL);
        inputData.horizontalSpan = 4;
        inputData.heightHint = 60;
        input.setLayoutData(inputData);

        send = new Button(root, SWT.PUSH);
        send.setText("Send");
        send.addListener(SWT.Selection, event -> sendPrompt());
        cancel = new Button(root, SWT.PUSH);
        cancel.setText("Cancel generation");
        cancel.setEnabled(false);
        cancel.addListener(SWT.Selection, event -> cancelGeneration());
        Button clear = new Button(root, SWT.PUSH);
        clear.setText("Clear conversation");
        clear.addListener(SWT.Selection, event -> clearConversation());
        new Label(root, SWT.NONE);

        contributeActions();
        refreshContent();
    }

    private void contributeActions() {
        var bars = getViewSite().getActionBars().getToolBarManager();
        bars.add(new Action("Refresh runtimes") {
            @Override
            public void run() {
                refreshContent();
            }
        });
    }

    @Override
    public void refreshContent() {
        Ui.async(() -> {
            Ui.run(RuntimeConnection.instance().api().runtimes(), list -> Ui.async(() -> {
                runtimes.clear();
                list.forEach(runtimes::add);
                String previous = runtimeCombo.getText();
                runtimeCombo.removeAll();
                for (JsonNode runtime : runtimes) {
                    if (!runtime.path("enabled").asBoolean()) {
                        continue;
                    }
                    String label = runtime.path("name").asText(runtime.path("id").asText(""))
                            + (runtime.path("default").asBoolean() ? " \u2605" : "");
                    runtimeCombo.add(label);
                    runtimeCombo.setData(label,
                            runtime.path("id").asText(""));
                }
                if (runtimes.isEmpty()) {
                    append("system", "No enabled runtimes. Register one in the Runtime Explorer.");
                    return;
                }
                int index = previous.isBlank() ? 0 : runtimeCombo.indexOf(previous);
                runtimeCombo.select(Math.max(index, 0));
                loadModels();
            }), "list runtimes");
        });
    }

    private void loadModels() {
        String runtimeId = selectedRuntimeId();
        if (runtimeId == null) {
            return;
        }
        String previous = modelCombo.getText();
        modelCombo.removeAll();
        Ui.run(RuntimeConnection.instance().api().runtimeModels(runtimeId), models ->
                Ui.async(() -> {
                    modelIds.clear();
                    for (JsonNode model : models) {
                        modelIds.add(model.path("id").asText(""));
                        modelCombo.add(model.path("id").asText(""));
                    }
                    int index = previous.isBlank() ? 0 : modelCombo.indexOf(previous);
                    if (modelIds.isEmpty()) {
                        modelCombo.add("(no models published)");
                        modelCombo.select(0);
                    } else {
                        modelCombo.select(Math.max(index, 0));
                    }
                }), "list models of " + runtimeId);
    }

    private String selectedRuntimeId() {
        int index = runtimeCombo.getSelectionIndex();
        if (index < 0) {
            return null;
        }
        return (String) runtimeCombo.getData(runtimeCombo.getItem(index));
    }

    private void sendPrompt() {
        String runtimeId = selectedRuntimeId();
        if (runtimeId == null) {
            append("system", "Select a runtime first.");
            return;
        }
        String model = modelCombo.getText();
        if (model.isBlank() || model.startsWith("(")) {
            append("system", "Select a model first.");
            return;
        }
        String prompt = input.getText().trim();
        if (prompt.isEmpty()) {
            return;
        }
        input.setText("");
        history.add(Map.of("role", "user", "content", prompt));
        append("user", prompt);
        append("assistant", "");
        send.setEnabled(false);
        cancel.setEnabled(true);
        transcript.setTopIndex(transcript.getLineCount() - 1);

        activeStream = RuntimeConnection.instance().api().streamInference(
                runtimeId, model, new ArrayList<>(history), 0.0, 0,
                new DCodeApiClient.InferenceStreamListener() {
                    private boolean failed;

                    @Override
                    public void onStart(String requestId, String runtime, String modelId) {
                        Ui.log("inference", "Inference started (runtime=" + runtimeId
                                + ", model=" + model + ")");
                    }

                    @Override
                    public void onToken(String token) {
                        Ui.async(() -> {
                            transcript.append(token);
                            transcript.setTopIndex(transcript.getLineCount() - 1);
                        });
                    }

                    @Override
                    public void onDone(JsonNode result) {
                        Ui.async(() -> {
                            if (failed) {
                                return;
                            }
                            history.add(Map.of("role", "assistant",
                                    "content", result.path("content").asText("")));
                            send.setEnabled(true);
                            cancel.setEnabled(false);
                            activeStream = null;
                        });
                    }

                    @Override
                    public void onError(String message) {
                        failed = true;
                        Ui.async(() -> {
                            append("system", "Inference error: " + message);
                            send.setEnabled(true);
                            cancel.setEnabled(false);
                            activeStream = null;
                        });
                    }
                });
    }

    private void cancelGeneration() {
        DCodeApiClient.InferenceStreamSession stream = activeStream;
        if (stream != null) {
            String requestId = stream.requestId();
            stream.cancel();
            if (requestId != null && !requestId.isBlank()) {
                Ui.run(RuntimeConnection.instance().api().cancelInference(requestId),
                        json -> Ui.log("inference", "Generation cancelled"),
                        "cancel inference");
            } else {
                Ui.log("inference", "Generation cancelled");
            }
            activeStream = null;
            send.setEnabled(true);
            cancel.setEnabled(false);
        }
    }

    private void clearConversation() {
        history.clear();
        transcript.setText("");
        append("system", "Conversation cleared. Runtime and model selection retained.");
    }

    private void append(String role, String content) {
        Ui.async(() -> {
            String prefix = switch (role) {
                case "user" -> "\n\u25b6 you\n";
                case "assistant" -> "\n\u25c8 ";
                default -> "\n\u00b7 system\n";
            };
            transcript.append(prefix + content + (role.equals("assistant") ? "" : "\n"));
            transcript.setTopIndex(transcript.getLineCount() - 1);
        });
    }

    @Override
    public void setFocus() {
        input.setFocus();
    }

    @Override
    public void dispose() {
        DCodeApiClient.InferenceStreamSession stream = activeStream;
        if (stream != null) {
            stream.cancel();
        }
        super.dispose();
    }
}
