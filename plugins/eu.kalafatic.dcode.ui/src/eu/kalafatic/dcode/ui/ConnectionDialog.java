package eu.kalafatic.dcode.ui;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/** Endpoint/token configuration dialog for local/remote runtime switching. */
public final class ConnectionDialog extends Dialog {

    private Text endpointText;
    private Text tokenText;

    public ConnectionDialog(Shell parent) {
        super(parent);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(new GridLayout(2, false));

        Label endpointLabel = new Label(area, SWT.NONE);
        endpointLabel.setText("Server endpoint:");
        endpointText = new Text(area, SWT.BORDER);
        endpointText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        endpointText.setText(RuntimeConnection.instance().endpoint() != null
                ? RuntimeConnection.instance().endpoint() : "http://127.0.0.1:48080");
        endpointText.setToolTipText("Leave the local endpoint to use the embedded runtime; "
                + "set a remote URL to connect to an independent headless server.");

        Label tokenLabel = new Label(area, SWT.NONE);
        tokenLabel.setText("Bearer token:");
        tokenText = new Text(area, SWT.BORDER | SWT.PASSWORD);
        tokenText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        tokenText.setToolTipText("Optional; only needed when the server requires "
                + "authentication. Stored in user preferences, never displayed.");

        return area;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Connect to DCode Runtime");
    }

    @Override
    protected void okPressed() {
        String endpoint = endpointText.getText().trim();
        String token = tokenText.getText();
        if (token.isBlank()) {
            token = null;
        }
        RuntimeConnection.instance().reconnect(endpoint.isEmpty() ? null : endpoint, token);
        Ui.log("Connecting to " + RuntimeConnection.instance().endpoint() + " …");
        Ui.refreshAll();
        super.okPressed();
    }
}
