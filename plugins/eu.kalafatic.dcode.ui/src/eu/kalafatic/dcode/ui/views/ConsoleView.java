package eu.kalafatic.dcode.ui.views;

import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

/** Console: system messages and live backend events (off the UI thread). */
public final class ConsoleView extends ViewPart implements Ui.Refreshable {

    private StyledText log;

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        log = new StyledText(root, SWT.MULTI | SWT.V_SCROLL | SWT.READ_ONLY);
        log.setLayoutData(new GridData(GridData.FILL_BOTH));
        log.append("DCode console ready.\n");
    }

    public void append(String kind, String message) {
        Ui.async(() -> {
            log.append("[" + kind + "] " + message + "\n");
            log.setTopIndex(log.getLineCount() - 1);
        });
    }

    public void appendEvent(String type, String data) {
        append("event", type + " " + truncate(data));
    }

    private static String truncate(String value) {
        return value.length() > 300 ? value.substring(0, 300) + "…" : value;
    }

    @Override
    public void refreshContent() {
        // console is append-only; nothing to refresh
    }

    @Override
    public void setFocus() {
        log.setFocus();
    }
}
