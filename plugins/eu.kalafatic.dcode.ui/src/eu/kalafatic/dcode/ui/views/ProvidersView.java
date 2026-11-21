package eu.kalafatic.dcode.ui.views;

import com.fasterxml.jackson.databind.JsonNode;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.action.Action;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

/** Providers view: LLM providers from the shared backend configuration. */
public final class ProvidersView extends ViewPart implements Ui.Refreshable {

    private StyledText content;

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        content = new StyledText(root, SWT.MULTI | SWT.V_SCROLL | SWT.READ_ONLY);
        content.setLayoutData(new GridData(GridData.FILL_BOTH));
        getViewSite().getActionBars().getToolBarManager().add(new Action("Refresh") {
            @Override
            public void run() {
                refreshContent();
            }
        });
        getViewSite().getActionBars().getToolBarManager().add(new Action("Test connection") {
            @Override
            public void run() {
                testFirstProvider();
            }
        });
        hookContextMenu();
        refreshContent();
    }

    /** Context menu: the same operations as the toolbar. */
    private void hookContextMenu() {
        org.eclipse.jface.action.MenuManager manager =
                new org.eclipse.jface.action.MenuManager();
        manager.setRemoveAllWhenShown(true);
        manager.addMenuListener(menu -> {
            menu.add(new Action("Test connection") {
                @Override
                public void run() {
                    testFirstProvider();
                }
            });
            menu.add(new Action("Refresh") {
                @Override
                public void run() {
                    refreshContent();
                }
            });
        });
        content.setMenu(manager.createContextMenu(content));
    }

    private void testFirstProvider() {
        Ui.run(RuntimeConnection.instance().api().providers(), providers -> {
            if (providers.size() == 0) {
                return;
            }
            String providerId = providers.get(0).path("id").asText();
            Ui.run(RuntimeConnection.instance().api().testProvider(providerId),
                    result -> Ui.log("provider " + providerId + " test: "
                            + (result.path("success").asBoolean() ? "OK" : "FAILED")
                            + " — " + result.path("message").asText()),
                    "provider test");
        }, "list providers");
    }

    @Override
    public void refreshContent() {
        Ui.async(() -> {
            content.setText("");
            Ui.run(RuntimeConnection.instance().api().providers(), providers -> {
                StringBuilder text = new StringBuilder();
                for (JsonNode provider : providers) {
                    text.append(provider.path("id").asText())
                            .append("  (").append(provider.path("name").asText())
                            .append(provider.path("local").asBoolean() ? ", local" : "")
                            .append(")\n  base: ").append(provider.path("baseUrl").asText())
                            .append("\n\n");
                }
                content.setText(text.toString());
            }, "list providers");
        });
    }

    @Override
    public void setFocus() {
        content.setFocus();
    }
}
