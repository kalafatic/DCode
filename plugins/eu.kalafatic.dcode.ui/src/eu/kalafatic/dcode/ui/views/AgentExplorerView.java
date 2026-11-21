package eu.kalafatic.dcode.ui.views;

import com.fasterxml.jackson.databind.JsonNode;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent Explorer: built-in and custom coding agents with their profiles. Creating
 * and editing custom agents runs on the backend (persisted, shared with the Web
 * IDE); the selected default agent is bound to new sessions.
 */
public final class AgentExplorerView extends ViewPart implements Ui.Refreshable {

    private Table table;
    private StyledText details;
    private final List<JsonNode> agents = new ArrayList<>();

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        table = new Table(root, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE
                | SWT.V_SCROLL);
        table.setHeaderVisible(true);
        TableColumn nameColumn = new TableColumn(table, SWT.LEFT);
        nameColumn.setText("Agent");
        nameColumn.setWidth(160);
        TableColumn roleColumn = new TableColumn(table, SWT.LEFT);
        roleColumn.setText("Role");
        roleColumn.setWidth(90);
        table.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        ((GridData) table.getLayoutData()).heightHint = 140;
        table.addListener(SWT.Selection, event -> showDetails());

        details = new StyledText(root, SWT.MULTI | SWT.V_SCROLL | SWT.READ_ONLY
                | SWT.WRAP);
        details.setLayoutData(new GridData(GridData.FILL_BOTH));

        getViewSite().getActionBars().getToolBarManager().add(new Action("New agent…") {
            @Override
            public void run() {
                new AgentDialog(getSite().getShell(), null).open();
                refreshContent();
            }
        });
        getViewSite().getActionBars().getToolBarManager().add(new Action("Edit…") {
            @Override
            public void run() {
                editSelected();
            }
        });
        getViewSite().getActionBars().getToolBarManager().add(new Action("Delete") {
            @Override
            public void run() {
                deleteSelected();
            }
        });
        getViewSite().getActionBars().getToolBarManager().add(new Action("Set default") {
            @Override
            public void run() {
                setDefaultSelected();
            }
        });
        getViewSite().getActionBars().getToolBarManager().add(new Action("Refresh") {
            @Override
            public void run() {
                refreshContent();
            }
        });
        hookContextMenu();
        refreshContent();
    }

    /** Context menu: the same operations as the toolbar (shared implementations). */
    private void hookContextMenu() {
        org.eclipse.jface.action.MenuManager manager =
                new org.eclipse.jface.action.MenuManager();
        manager.setRemoveAllWhenShown(true);
        manager.addMenuListener(menu -> {
            menu.add(new Action("Select as default") {
                @Override
                public void run() {
                    setDefaultSelected();
                }
            });
            menu.add(new Action("Configure…") {
                @Override
                public void run() {
                    editSelected();
                }
            });
        });
        table.setMenu(manager.createContextMenu(table));
    }

    /** Opens the edit dialog for the selected custom agent (shared by toolbar+menu). */
    private void editSelected() {
        JsonNode agent = selected();
        if (agent == null) {
            return;
        }
        if (!agent.path("builtin").asBoolean()) {
            new AgentDialog(getSite().getShell(), agent).open();
            refreshContent();
        } else {
            Ui.log("warning", "Built-in agents are immutable");
        }
    }

    /** Deletes the selected custom agent (shared by the toolbar Delete action). */
    private void deleteSelected() {
        JsonNode agent = selected();
        if (agent == null || agent.path("builtin").asBoolean()) {
            Ui.log("warning", "Only custom agents can be deleted");
            return;
        }
        Ui.run(RuntimeConnection.instance().api().deleteAgent(
                        agent.path("id").asText("")),
                json -> refreshContent(), "delete agent");
    }

    /** Sets the selected agent as the platform default (shared by toolbar+menu). */
    private void setDefaultSelected() {
        JsonNode agent = selected();
        if (agent == null) {
            return;
        }
        Ui.run(RuntimeConnection.instance().api().setDefaultAgent(
                        agent.path("id").asText("")),
                json -> {
                    Ui.log("harness", "Default agent: "
                            + agent.path("name").asText(""));
                    refreshContent();
                }, "set default agent");
    }

    private JsonNode selected() {
        int index = table.getSelectionIndex();
        return index >= 0 && index < agents.size() ? agents.get(index) : null;
    }

    private void showDetails() {
        JsonNode agent = selected();
        if (agent == null) {
            details.setText("");
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append(agent.path("name").asText(""))
                .append(agent.path("builtin").asBoolean() ? " (built-in)" : "")
                .append(agent.path("default").asBoolean() ? " · default" : "")
                .append('\n');
        text.append("Role: ").append(agent.path("role").asText("")).append('\n');
        text.append("Description: ").append(agent.path("description").asText(""))
                .append("\n\n");
        text.append("System instructions:\n")
                .append(agent.path("systemInstructions").asText(""))
                .append('\n');
        JsonNode permissions = agent.path("toolPermissions");
        if (permissions.isArray() && permissions.size() > 0) {
            text.append("\nTool permissions: ").append(permissions.toString())
                    .append('\n');
        }
        JsonNode restrictions = agent.path("restrictions");
        if (restrictions.isArray() && restrictions.size() > 0) {
            text.append("Restrictions: ").append(restrictions.toString()).append('\n');
        }
        if (!agent.path("preferredRuntimeId").asText("").isBlank()) {
            text.append("\nPreferred runtime: ")
                    .append(agent.path("preferredRuntimeId").asText(""));
            if (!agent.path("preferredModel").asText("").isBlank()) {
                text.append(" / ").append(agent.path("preferredModel").asText(""));
            }
            text.append('\n');
        }
        details.setText(text.toString());
    }

    @Override
    public void refreshContent() {
        Ui.async(() -> {
            if (!RuntimeConnection.instance().isUsableForRequests()) {
                table.removeAll();
                return;
            }
            Ui.run(RuntimeConnection.instance().api().agents(), list -> Ui.async(() -> {
                agents.clear();
                table.removeAll();
                for (JsonNode agent : list) {
                    agents.add(agent);
                    TableItem item = new TableItem(table, SWT.NONE);
                    item.setText(0, agent.path("name").asText("")
                            + (agent.path("default").asBoolean() ? " ★" : ""));
                    item.setText(1, agent.path("role").asText(""));
                    item.setData(agent);
                }
            }), "list agents");
        });
    }

    @Override
    public void setFocus() {
        table.setFocus();
    }

    /** Create/edit dialog for custom agents (thin client; persistence on backend). */
    public static final class AgentDialog extends Dialog {

        private final JsonNode existing;
        private Text nameText;
        private Text roleText;
        private Text descriptionText;
        private Text instructionsText;

        public AgentDialog(org.eclipse.swt.widgets.Shell parent, JsonNode existing) {
            super(parent);
            this.existing = existing;
        }

        @Override
        protected void configureShell(org.eclipse.swt.widgets.Shell shell) {
            super.configureShell(shell);
            shell.setText(existing == null ? "New agent" : "Edit agent");
        }

        @Override
        protected org.eclipse.swt.widgets.Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            area.setLayout(new GridLayout(2, false));

            new Label(area, SWT.NONE).setText("Name");
            nameText = new Text(area, SWT.BORDER);
            nameText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

            new Label(area, SWT.NONE).setText("Role");
            roleText = new Text(area, SWT.BORDER);
            roleText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

            new Label(area, SWT.NONE).setText("Description");
            descriptionText = new Text(area, SWT.BORDER);
            descriptionText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

            new Label(area, SWT.NONE).setText("System instructions");
            instructionsText = new Text(area, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL
                    | SWT.WRAP);
            GridData instructionsData = new GridData(GridData.FILL_BOTH);
            instructionsData.horizontalSpan = 2;
            instructionsData.heightHint = 160;
            instructionsText.setLayoutData(instructionsData);

            if (existing != null) {
                nameText.setText(existing.path("name").asText(""));
                roleText.setText(existing.path("role").asText("MAIN"));
                descriptionText.setText(existing.path("description").asText(""));
                instructionsText.setText(existing.path("systemInstructions")
                        .asText(""));
            } else {
                roleText.setText("MAIN");
            }
            return area;
        }

        @Override
        protected void okPressed() {
            String name = nameText.getText().trim();
            if (name.isEmpty()) {
                return;
            }
            String id = existing != null ? existing.path("id").asText("") : null;
            Ui.run(RuntimeConnection.instance().api().saveAgent(id, name,
                            descriptionText.getText().trim(),
                            roleText.getText().trim().isEmpty() ? "MAIN"
                                    : roleText.getText().trim(),
                            instructionsText.getText()),
                    json -> Ui.log("success", "Agent saved: "
                            + json.path("name").asText("")),
                    "save agent");
            super.okPressed();
        }
    }
}
