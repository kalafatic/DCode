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
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

/**
 * AI chat: tasks are submitted to the active session through the backend execution
 * engine; messages stream in via SSE (authoritative). No chat logic lives here.
 */
public final class ChatView extends ViewPart implements Ui.Refreshable, Ui.SessionListener {

    private StyledText transcript;
    private Text input;

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        transcript = new StyledText(root, SWT.MULTI | SWT.V_SCROLL | SWT.READ_ONLY
                | SWT.WRAP);
        transcript.setLayoutData(new GridData(GridData.FILL_BOTH));

        Composite actions = new Composite(root, SWT.NONE);
        actions.setLayout(new GridLayout(3, false));
        actions.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Button submit = new Button(actions, SWT.PUSH);
        submit.setText("Submit task");
        submit.addListener(SWT.Selection, event -> submitTask());
        Button cancel = new Button(actions, SWT.PUSH);
        cancel.setText("Cancel execution");
        cancel.addListener(SWT.Selection, event -> cancelExecution());
        Button refresh = new Button(actions, SWT.PUSH);
        refresh.setText("Reload");
        refresh.addListener(SWT.Selection, event -> refreshContent());

        input = new Text(root, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.WRAP);
        input.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        ((GridData) input.getLayoutData()).heightHint = 80;
        input.setToolTipText("Task instructions for the active session. Execution runs "
                + "on the backend; do not wait on this input.");

        Ui.addSessionListener(this);
        refreshContent();
    }

    private void submitTask() {
        Session session = RuntimeConnection.instance().activeSession();
        if (session == null) {
            Ui.log("error", "Open a session first (Sessions view)");
            return;
        }
        String task = input.getText().trim();
        if (task.isEmpty()) {
            return;
        }
        input.setText("");
        append("user", task);
        Ui.run(RuntimeConnection.instance().api().startTask(session.getId(), task),
                json -> Ui.log("Task submitted to session " + session.getId()),
                "start task");
    }

    private void cancelExecution() {
        Session session = RuntimeConnection.instance().activeSession();
        if (session == null) {
            return;
        }
        Ui.run(RuntimeConnection.instance().api().cancelSession(session.getId()),
                json -> Ui.log("Cancellation requested for " + session.getId()),
                "cancel session");
    }

    @Override
    public void refreshContent() {
        Ui.async(() -> {
            Session session = RuntimeConnection.instance().activeSession();
            transcript.setText("");
            if (session == null) {
                append("system", "No active session. Open one from the Sessions view.");
                return;
            }
            append("system", "Session " + session.getTitle() + " [" + session.getState() + "]");
            Ui.run(RuntimeConnection.instance().api().messages(session.getId()),
                    messages -> Ui.async(() -> {
                        for (JsonNode message : messages) {
                            append(message.path("role").asText("system").toLowerCase(),
                                    message.path("content").asText(""));
                        }
                        transcript.setTopIndex(transcript.getLineCount() - 1);
                    }), "load messages");
        });
    }

    @Override
    public void sessionChanged(Session session) {
        refreshContent();
    }

    private void append(String role, String content) {
        Ui.async(() -> {
            String prefix = switch (role) {
                case "user" -> "\n▶ you\n";
                case "assistant" -> "\n◆ agent\n";
                case "tool" -> "\n▸ tool\n";
                default -> "\n· system\n";
            };
            transcript.append(prefix + content + "\n");
            transcript.setTopIndex(transcript.getLineCount() - 1);
        });
    }

    @Override
    public void setFocus() {
        input.setFocus();
    }

    @Override
    public void dispose() {
        Ui.removeSessionListener(this);
        super.dispose();
    }
}
