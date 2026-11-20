package eu.kalafatic.dcode.ui.views;

import com.fasterxml.jackson.databind.JsonNode;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.action.Action;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.part.ViewPart;

import java.util.ArrayList;
import java.util.List;

/**
 * Port &amp; Network Monitor: actual runtime port state of the DCode services —
 * expected family port, actually bound port, bind address, protocol, state and
 * conflict diagnostics (including the occupying process where identifiable).
 * "ONLINE" means listening on the reported port; stale defaults are never shown.
 */
public final class PortMonitorView extends ViewPart implements Ui.Refreshable {

    private Table table;
    private final List<JsonNode> ports = new ArrayList<>();

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        table = new Table(root, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE
                | SWT.V_SCROLL);
        table.setHeaderVisible(true);
        addColumn("Service", 130);
        addColumn("Expected", 70);
        addColumn("Actual", 70);
        addColumn("Bind", 110);
        addColumn("Protocol", 70);
        addColumn("State", 90);
        addColumn("Conflict", 150);
        table.setLayoutData(new GridData(GridData.FILL_BOTH));

        getViewSite().getActionBars().getToolBarManager().add(new Action("Refresh") {
            @Override
            public void run() {
                refreshContent();
            }
        });
        getViewSite().getActionBars().getToolBarManager().add(new Action("Copy endpoint") {
            @Override
            public void run() {
                JsonNode port = selected();
                if (port == null || !port.path("listening").asBoolean()) {
                    Ui.log("warning", "Select a LISTENING service");
                    return;
                }
                String endpoint = "http://" + port.path("bindAddress").asText()
                        + ":" + port.path("actualPort").asInt();
                Clipboard clipboard = new Clipboard(table.getDisplay());
                clipboard.setContents(new Object[]{endpoint},
                        new Transfer[]{TextTransfer.getInstance()});
                clipboard.dispose();
                Ui.log("info", "Copied " + endpoint);
            }
        });
        refreshContent();
    }

    private void addColumn(String title, int width) {
        TableColumn column = new TableColumn(table, SWT.LEFT);
        column.setText(title);
        column.setWidth(width);
    }

    private JsonNode selected() {
        int index = table.getSelectionIndex();
        return index >= 0 && index < ports.size() ? ports.get(index) : null;
    }

    @Override
    public void refreshContent() {
        Ui.async(() -> {
            if (!RuntimeConnection.instance().isUsableForRequests()) {
                table.removeAll();
                return;
            }
            Ui.run(RuntimeConnection.instance().api().ports(), list -> Ui.async(() -> {
                ports.clear();
                table.removeAll();
                for (JsonNode port : list) {
                    ports.add(port);
                    TableItem item = new TableItem(table, SWT.NONE);
                    item.setText(new String[]{
                            port.path("name").asText(port.path("id").asText("")),
                            String.valueOf(port.path("expectedPort").asInt()),
                            port.path("actualPort").asInt() > 0
                                    ? String.valueOf(port.path("actualPort").asInt())
                                    : "—",
                            port.path("bindAddress").asText(""),
                            port.path("protocol").asText(""),
                            port.path("state").asText(""),
                            port.path("conflict").asBoolean()
                                    ? "conflict: pid " + port.path("occupantPid")
                                    .asInt(-1) + " (" + port.path("occupantProcess")
                                    .asText("?") + ")"
                                    : ""});
                    item.setData(port);
                }
            }), "list ports");
        });
    }

    @Override
    public void setFocus() {
        table.setFocus();
    }
}
