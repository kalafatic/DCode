package eu.kalafatic.dcode.ui.views;

import com.fasterxml.jackson.databind.JsonNode;
import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.action.Action;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

/**
 * Agent activity: the audited tool executions of the active session (backend
 * ToolEngine records), refreshed live via SSE and on demand. Secrets are never shown —
 * the backend masks them; raw output is available for debugging.
 */
public final class ActivityView extends ViewPart implements Ui.Refreshable {

    private StyledText log;

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        log = new StyledText(root, SWT.MULTI | SWT.V_SCROLL | SWT.READ_ONLY);
        log.setLayoutData(new GridData(GridData.FILL_BOTH));
        getViewSite().getActionBars().getToolBarManager().add(new Action("Refresh") {
            @Override
            public void run() {
                refreshContent();
            }
        });
        refreshContent();
    }

    @Override
    public void refreshContent() {
        Ui.async(() -> {
            Session session = RuntimeConnection.instance().activeSession();
            log.setText("");
            if (session == null) {
                log.append("No active session.\n");
                return;
            }
            Ui.run(RuntimeConnection.instance().api().toolCalls(session.getId()),
                    calls -> Ui.async(() -> {
                        log.append("Tool calls for session " + session.getId() + ":\n\n");
                        for (JsonNode call : calls) {
                            log.append("▸ " + call.path("toolName").asText("?")
                                    + "  [" + call.path("state").asText("?") + "]\n");
                            if (call.hasNonNull("errorMessage")
                                    && !call.path("errorMessage").asText().isEmpty()) {
                                log.append("  error: "
                                        + call.path("errorMessage").asText() + "\n");
                            }
                            if (call.hasNonNull("argumentsJson")) {
                                log.append("  args: " + truncate(
                                        call.path("argumentsJson").asText()) + "\n");
                            }
                        }
                    }), "load tool calls");
        });
    }

    private static String truncate(String value) {
        return value.length() > 200 ? value.substring(0, 200) + "…" : value;
    }

    @Override
    public void setFocus() {
        log.setFocus();
    }
}
