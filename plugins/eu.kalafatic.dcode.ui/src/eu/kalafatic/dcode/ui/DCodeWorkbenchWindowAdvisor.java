package eu.kalafatic.dcode.ui;

import eu.kalafatic.dcode.client.BackendIdentity;
import eu.kalafatic.dcode.client.ConnectionState;
import org.eclipse.swt.graphics.Point;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.application.ActionBarAdvisor;
import org.eclipse.ui.application.IActionBarConfigurer;
import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.eclipse.ui.application.WorkbenchWindowAdvisor;

/**
 * Main window configuration: size, title, status line, action bar. The status line
 * carries the live connection state and the detected backend identity.
 */
public final class DCodeWorkbenchWindowAdvisor extends WorkbenchWindowAdvisor {

    private org.eclipse.swt.widgets.Listener helpKeyFilter;

    public DCodeWorkbenchWindowAdvisor(IWorkbenchWindowConfigurer configurer) {
        super(configurer);
    }

    @Override
    public ActionBarAdvisor createActionBarAdvisor(IActionBarConfigurer configurer) {
        return new DCodeActionBarAdvisor(configurer);
    }

    @Override
    public void preWindowOpen() {
        DCodeWorkbenchProbe.windowAdvisorCalled = true;
        IWorkbenchWindowConfigurer configurer = getWindowConfigurer();
        configurer.setInitialSize(new Point(1440, 900));
        configurer.setShowStatusLine(true);
        configurer.setShowProgressIndicator(true);
        configurer.setTitle("DCode — Darwin Code");
    }

    @Override
    public void postWindowOpen() {
        final org.eclipse.jface.action.IStatusLineManager statusLine =
                getWindowConfigurer().getActionBarConfigurer().getStatusLineManager();
        RuntimeConnection.instance().addStateListener(
                new RuntimeConnection.ConnectionStateListener() {
                    private boolean wizardShown;

                    @Override
                    public void stateChanged(ConnectionState state,
                                             BackendIdentity identity,
                                             String diagnostic) {
                        Ui.async(() -> {
                            if (statusLine == null) {
                                return;
                            }
                            String text = "Connection: " + state.name();
                            if (identity != null && identity.isDCode()) {
                                text += " — " + identity.application() + " "
                                        + identity.version()
                                        + (identity.ready() ? " (ready)" : "");
                            } else if (state == ConnectionState.ERROR
                                    && diagnostic != null && !diagnostic.isBlank()) {
                                text += " — " + truncate(diagnostic, 120);
                            }
                            statusLine.setMessage(text);
                            maybeOpenFirstRunWizard(state);
                        });
                    }

                    /** First run: no providers configured yet → guide the user. */
                    private void maybeOpenFirstRunWizard(ConnectionState state) {
                        if (wizardShown || state != ConnectionState.READY) {
                            return;
                        }
                        RuntimeConnection.instance().api().runtimes()
                                .whenComplete((runtimes, throwable) -> Ui.async(() -> {
                                    if (wizardShown) {
                                        return;
                                    }
                                    wizardShown = true;
                                    if (throwable == null && runtimes.size() == 0
                                            && getWindowConfigurer().getWindow()
                                            .getShell() != null
                                            && !getWindowConfigurer().getWindow()
                                            .getShell().isDisposed()) {
                                        new eu.kalafatic.dcode.ui.views
                                                .AiEnvironmentWizardDialog(
                                                getWindowConfigurer().getWindow()
                                                        .getShell()).open();
                                    }
                                }));
                    }
                });
        installContextHelpKey();
    }

    /**
     * F1 context help without {@code org.eclipse.help}: a display-level key filter
     * maps plain F1 (no modifiers) to the served help page section of the active
     * part. Only F1 is consumed; every other key passes through untouched.
     */
    private void installContextHelpKey() {
        if (helpKeyFilter != null) {
            return;
        }
        helpKeyFilter = event -> {
            if (!(event instanceof org.eclipse.swt.widgets.Event swtEvent)
                    || swtEvent.type != org.eclipse.swt.SWT.KeyDown
                    || swtEvent.keyCode != org.eclipse.swt.SWT.F1
                    || swtEvent.stateMask != 0) {
                return;
            }
            String partId = null;
            try {
                var window = org.eclipse.ui.PlatformUI.getWorkbench()
                        .getActiveWorkbenchWindow();
                if (window != null && window.getActivePage() != null
                        && window.getActivePage().getActivePart() != null) {
                    partId = window.getActivePage().getActivePart().getSite().getId();
                }
            } catch (RuntimeException ignored) {
                // workbench not ready: open the general context section
            }
            HelpLauncher.contextHelpFor(partId);
            swtEvent.doit = false;
        };
        org.eclipse.swt.widgets.Display.getDefault().addFilter(
                org.eclipse.swt.SWT.KeyDown, helpKeyFilter);
    }

    @Override
    public void dispose() {
        if (helpKeyFilter != null) {
            org.eclipse.swt.widgets.Display display =
                    org.eclipse.swt.widgets.Display.getCurrent() != null
                            ? org.eclipse.swt.widgets.Display.getCurrent()
                            : org.eclipse.swt.widgets.Display.getDefault();
            if (display != null && !display.isDisposed()) {
                display.removeFilter(org.eclipse.swt.SWT.KeyDown, helpKeyFilter);
            }
            helpKeyFilter = null;
        }
        super.dispose();
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }
}
