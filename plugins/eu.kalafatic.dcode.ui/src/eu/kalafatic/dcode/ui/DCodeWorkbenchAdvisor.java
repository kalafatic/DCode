package eu.kalafatic.dcode.ui;
import org.eclipse.ui.application.IWorkbenchConfigurer;
import org.eclipse.ui.application.IWorkbenchWindowConfigurer;
import org.eclipse.ui.application.WorkbenchAdvisor;
import org.eclipse.ui.application.WorkbenchWindowAdvisor;

/** Workbench configuration: default perspective, branding, clean layout defaults. */
public final class DCodeWorkbenchAdvisor extends WorkbenchAdvisor {

    private static final String PERSPECTIVE_ID = "eu.kalafatic.dcode.ui.perspective";

    @Override
    public WorkbenchWindowAdvisor createWorkbenchWindowAdvisor(
            IWorkbenchWindowConfigurer configurer) {
        return new DCodeWorkbenchWindowAdvisor(configurer);
    }

    @Override
    public String getInitialWindowPerspectiveId() {
        return PERSPECTIVE_ID;
    }

    @Override
    public void initialize(IWorkbenchConfigurer configurer) {
        super.initialize(configurer);
    }

    @Override
    public void preStartup() {
        super.preStartup();
        // Initialize the runtime connection: local embedded server when available,
        // otherwise the user-configured remote endpoint.
        RuntimeConnection.instance().initialize();
    }

    /**
     * Opens the main harness editor automatically once the initial window and page
     * exist (EVO lifecycle pattern: postStartup runs after the initial window is
     * created). {@link org.eclipse.ui.IWorkbenchPage#openEditor} returns the restored
     * or already-open editor when the input matches, so this never creates duplicates
     * and covers workbench-state restoration.
     */
    @Override
    public void postStartup() {
        super.postStartup();
        Ui.log("info", "Workbench startup complete; opening the inference editor…");
        org.eclipse.swt.widgets.Display display =
                org.eclipse.ui.PlatformUI.getWorkbench().getDisplay();
        display.asyncExec(DCodeWorkbenchAdvisor::openInferenceEditor);
    }

    private static void openInferenceEditor() {
        try {
            org.eclipse.ui.IWorkbenchWindow window =
                    org.eclipse.ui.PlatformUI.getWorkbench()
                            .getActiveWorkbenchWindow();
            if (window == null) {
                Ui.log("warning", "Cannot open the inference editor: no workbench "
                        + "window yet.");
                return;
            }
            org.eclipse.ui.IWorkbenchPage page = window.getActivePage();
            if (page == null) {
                Ui.log("warning", "Cannot open the inference editor: no active page "
                        + "yet.");
                return;
            }
            openInferenceEditor(page);
        } catch (RuntimeException e) {
            Ui.log("error", "Cannot open the inference editor: " + e.getMessage());
        }
    }

    /**
     * Opens (or activates) the shared harness editor on the given page. The editor
     * input is stable, so this converges on a single instance — used by startup
     * auto-open, the navigator double-click and perspective-reset recovery.
     */
    public static void openInferenceEditor(org.eclipse.ui.IWorkbenchPage page) {
        try {
            eu.kalafatic.dcode.ui.editors.DCodeMultiPageEditorInput input =
                    new eu.kalafatic.dcode.ui.editors.DCodeMultiPageEditorInput();
            org.eclipse.ui.IEditorPart editor = page.openEditor(input,
                    eu.kalafatic.dcode.ui.editors.DCodeMultiPageEditor.EDITOR_ID);
            page.activate(editor);
            Ui.log("success", "Inference editor ready: " + editor.getTitle());
        } catch (org.eclipse.ui.PartInitException e) {
            Ui.logDetailed("error", "Failed to open the inference editor",
                    java.util.List.of(
                            "Editor: "
                                    + eu.kalafatic.dcode.ui.editors
                                    .DCodeMultiPageEditor.EDITOR_ID,
                            "Detail: " + e.getClass().getSimpleName() + ": "
                                    + e.getMessage(),
                            "Cause: " + (e.getCause() == null ? "none"
                                    : e.getCause().getClass().getSimpleName()
                                            + ": " + e.getCause().getMessage())));
        }
    }
}
