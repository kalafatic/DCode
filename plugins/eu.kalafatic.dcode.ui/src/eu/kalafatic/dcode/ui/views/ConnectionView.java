package eu.kalafatic.dcode.ui.views;

import com.fasterxml.jackson.databind.JsonNode;
import eu.kalafatic.dcode.ui.ConnectionDialog;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.action.Action;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

/** Connection view: runtime mode, endpoint, health and reconnect actions. */
public final class ConnectionView extends ViewPart implements Ui.Refreshable {

    private StyledText content;

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        content = new StyledText(root, SWT.MULTI | SWT.V_SCROLL | SWT.READ_ONLY);
        content.setLayoutData(new GridData(GridData.FILL_BOTH));

        getViewSite().getActionBars().getToolBarManager().add(new Action("Health") {
            @Override
            public void run() {
                checkHealth();
            }
        });
        getViewSite().getActionBars().getToolBarManager().add(new Action("Connect…") {
            @Override
            public void run() {
                new ConnectionDialog(getSite().getShell()).open();
                refreshContent();
            }
        });
        refreshContent();
    }

    private void checkHealth() {
        Ui.run(RuntimeConnection.instance().api().health(),
                health -> Ui.log("health: "
                        + health.path("status").asText("?")
                        + (health.path("ready").asBoolean() ? " (ready)" : " (not ready)")),
                "health");
    }

    @Override
    public void refreshContent() {
        Ui.async(() -> {
            StringBuilder text = new StringBuilder();
            text.append("mode: ").append(RuntimeConnection.instance().isLocalMode()
                    ? "local (embedded runtime)" : "remote").append('\n');
            text.append("endpoint: ").append(RuntimeConnection.instance().endpoint())
                    .append("\n\n");
            text.append("The RCP workbench consumes the same authoritative backend "
                    + "services as the web UI — sessions, tools, repositories and "
                    + "events are shared across clients.");
            content.setText(text.toString());
        });
    }

    @Override
    public void setFocus() {
        content.setFocus();
    }
}
