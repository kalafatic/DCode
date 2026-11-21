package eu.kalafatic.dcode.ui;

import org.eclipse.ui.IFolderLayout;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPerspectiveFactory;

/**
 * Default DCode perspective, aligned with the EVO reference workbench layout:
 *
 * <pre>
 * +------------------------------------------------------------+
 * | Application main menu (workbench menu bar)                 |
 * +--------------+---------------------------------------------+
 * | Navigator    |        Editor area                          |
 * | (top-left)   |   (MultiPageEditor: Inference + Settings)   |
 * +--------------+                                             |
 * | Console      |                                             |
 * | (bottom-left)|                                             |
 * +--------------+---------------------------------------------+
 * </pre>
 *
 * <p>Left sidebar: Navigator on top, Console below it — the same proportions as EVO
 * (left 0.20 of the editor area, console at 0.50 of the left folder). Every other
 * DCode view remains registered and is reachable through Window ▸ Show View.</p>
 */
public final class DCodePerspectiveFactory implements IPerspectiveFactory {

    /** Top-left folder (Navigator), mirroring EVO's {@code EFolder.TOP_LEFT}. */
    public static final String FOLDER_TOP_LEFT = "eu.kalafatic.dcode.ui.topLeft";

    /** Bottom-left folder (Console), mirroring EVO's {@code EFolder.BOTTOM_LEFT}. */
    public static final String FOLDER_BOTTOM_LEFT = "eu.kalafatic.dcode.ui.bottomLeft";

    @Override
    public void createInitialLayout(IPageLayout layout) {
        String editorArea = layout.getEditorArea();
        layout.setEditorAreaVisible(true);

        IFolderLayout left = layout.createFolder(FOLDER_TOP_LEFT,
                IPageLayout.LEFT, 0.20f, editorArea);
        left.addView("eu.kalafatic.dcode.ui.views.workspace");

        IFolderLayout bottomLeft = layout.createFolder(FOLDER_BOTTOM_LEFT,
                IPageLayout.BOTTOM, 0.50f, FOLDER_TOP_LEFT);
        bottomLeft.addView("eu.kalafatic.dcode.ui.views.console");

        layout.addActionSet(IPageLayout.ID_NAVIGATE_ACTION_SET);

        addViewShortcuts(layout);
    }

    /**
     * All other DCode views stay registered and reachable from Show View,
     * exactly like the auxiliary views of the EVO perspective.
     */
    private void addViewShortcuts(IPageLayout layout) {
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.workspace");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.console");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.connection");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.inference");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.aiworkspace");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.runtimeselector");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.runtimeexplorer");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.runtimes");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.models");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.modelmanager");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.directchat");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.providers");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.sessions");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.sessionnavigator");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.chat");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.agentexplorer");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.activity");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.changes");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.javatools");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.portmonitor");
        layout.addShowViewShortcut(IPageLayout.ID_OUTLINE);
        layout.addShowViewShortcut(IPageLayout.ID_PROP_SHEET);
        layout.addShowViewShortcut(IPageLayout.ID_PROGRESS_VIEW);
    }
}
