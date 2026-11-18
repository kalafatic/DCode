package eu.kalafatic.dcode.ui.views;

import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

/** Dialog to add a repository to a workspace through the backend API. */
public final class AddRepositoryDialog
        extends org.eclipse.jface.dialogs.Dialog {

    private final Workspace workspace;
    private Text nameText;
    private Text pathText;
    private Text remoteText;
    private org.eclipse.swt.widgets.Button readOnlyButton;

    public AddRepositoryDialog(org.eclipse.swt.widgets.Shell shell, Workspace workspace) {
        super(shell);
        this.workspace = workspace;
    }

    @Override
    protected org.eclipse.swt.widgets.Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(new GridLayout(2, false));

        new Label(area, SWT.NONE).setText("Name:");
        nameText = new Text(area, SWT.BORDER);
        nameText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        new Label(area, SWT.NONE).setText("Local path:");
        pathText = new Text(area, SWT.BORDER);
        pathText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        pathText.setToolTipText("Server-side repository directory");

        new Label(area, SWT.NONE).setText("Remote URL:");
        remoteText = new Text(area, SWT.BORDER);
        remoteText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        readOnlyButton = new org.eclipse.swt.widgets.Button(area, SWT.CHECK);
        readOnlyButton.setText("Read-only repository");
        new org.eclipse.swt.widgets.Label(area, SWT.NONE);

        return area;
    }

    @Override
    protected void configureShell(org.eclipse.swt.widgets.Shell shell) {
        super.configureShell(shell);
        shell.setText("Add repository to " + workspace.name());
    }

    @Override
    protected void okPressed() {
        RuntimeConnection.instance().api().addRepository(workspace.id(),
                        nameText.getText(), pathText.getText(), remoteText.getText(),
                        readOnlyButton.getSelection())
                .whenComplete((updated, throwable) -> Ui.async(() -> {
                    if (throwable != null) {
                        Ui.log("error", "add repository failed: " + throwable.getMessage());
                        return;
                    }
                    Ui.log("Repository '" + nameText.getText() + "' added to "
                            + workspace.name());
                    close();
                }));
    }
}
