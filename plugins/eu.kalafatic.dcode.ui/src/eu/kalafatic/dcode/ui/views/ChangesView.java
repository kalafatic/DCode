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
import org.eclipse.ui.part.ViewPart;

/**
 * Changes & review: live per-repository change state of the active session plus the
 * backend review approve/reject flow. Diffs are grouped per repository — no
 * cross-repository atomicity is ever implied.
 */
public final class ChangesView extends ViewPart implements Ui.Refreshable, Ui.SessionListener {

    private StyledText content;
    private Button approveButton;
    private Button rejectButton;

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        Composite actions = new Composite(root, SWT.NONE);
        actions.setLayout(new GridLayout(3, false));
        actions.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        approveButton = new Button(actions, SWT.PUSH);
        approveButton.setText("Approve review");
        approveButton.setEnabled(false);
        approveButton.addListener(SWT.Selection, event -> decide(true));
        rejectButton = new Button(actions, SWT.PUSH);
        rejectButton.setText("Reject review");
        rejectButton.setEnabled(false);
        rejectButton.addListener(SWT.Selection, event -> decide(false));
        Button refresh = new Button(actions, SWT.PUSH);
        refresh.setText("Refresh");
        refresh.addListener(SWT.Selection, event -> refreshContent());

        content = new StyledText(root, SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL
                | SWT.READ_ONLY);
        content.setLayoutData(new GridData(GridData.FILL_BOTH));
        content.setFont(org.eclipse.jface.resource.JFaceResources.getTextFont());

        getViewSite().getActionBars().getToolBarManager().add(new Action("Refresh") {
            @Override
            public void run() {
                refreshContent();
            }
        });
        Ui.addSessionListener(this);
        refreshContent();
    }

    @Override
    public void refreshContent() {
        Ui.async(() -> {
            Session session = RuntimeConnection.instance().activeSession();
            content.setText("");
            approveButton.setEnabled(false);
            rejectButton.setEnabled(false);
            if (session == null) {
                content.append("No active session.\n");
                return;
            }
            Ui.run(RuntimeConnection.instance().api().changes(session.getId()),
                    changes -> Ui.async(() -> {
                        StringBuilder text = new StringBuilder();
                        text.append("Session ").append(session.getId())
                                .append(" [" + session.getState() + "] — ")
                                .append(changes.path("state").asText("")).append("\n\n");
                        for (JsonNode repository : changes.path("repositories")) {
                            text.append("=== ").append(repository.path("name").asText())
                                    .append(" (branch ").append(
                                            repository.path("branch").asText("?"))
                                    .append(") ===\n");
                            if (repository.has("error")) {
                                text.append("error: ").append(
                                        repository.path("error").asText()).append("\n\n");
                                continue;
                            }
                            text.append("changed files (").append(
                                            repository.path("changedCount").asInt())
                                    .append("): ");
                            for (JsonNode file : repository.path("changedFiles")) {
                                text.append(file.asText()).append(", ");
                            }
                            text.append("\n\n").append(repository.path("diff").asText(""))
                                    .append("\n\n");
                        }
                        content.setText(text.toString());
                    }), "load changes");
            // pending reviews → enable approve/reject
            Ui.run(RuntimeConnection.instance().api().reviews(session.getId()),
                    reviews -> Ui.async(() -> {
                        for (JsonNode review : reviews) {
                            if ("PENDING".equals(review.path("status").asText())) {
                                approveButton.setEnabled(true);
                                rejectButton.setEnabled(true);
                                approveButton.setData(review.path("id").asText());
                                rejectButton.setData(review.path("id").asText());
                            }
                        }
                    }), "load reviews");
        });
    }

    private void decide(boolean approve) {
        Button source = approve ? approveButton : rejectButton;
        String reviewId = (String) source.getData();
        if (reviewId == null) {
            return;
        }
        var future = approve
                ? RuntimeConnection.instance().api().approveReview(reviewId, "rcp",
                "decided in RCP")
                : RuntimeConnection.instance().api().rejectReview(reviewId, "rcp",
                        "rejected in RCP");
        Ui.run(future, json -> {
            Ui.log(approve ? "Review approved" : "Review rejected");
            refreshContent();
        }, "review decision");
    }

    @Override
    public void sessionChanged(Session session) {
        refreshContent();
    }

    @Override
    public void setFocus() {
        content.setFocus();
    }

    @Override
    public void dispose() {
        Ui.removeSessionListener(this);
        super.dispose();
    }
}
