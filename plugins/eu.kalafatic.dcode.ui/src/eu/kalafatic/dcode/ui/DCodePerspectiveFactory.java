package eu.kalafatic.dcode.ui;

import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPerspectiveFactory;

/**
 * Default DCode perspective: professional AI coding IDE layout.
 *
 * <pre>
 * +------------------------------------------------------------+
 * | AI Selection (harness · provider · model · agent · status)  |
 * +--------------+------------------------------+--------------+
 * | Project      |        Editor area           | AI Workspace |
 * | Explorer     |  (file editors, welcome)     | (chat /      |
 * |              |                              |  commander)  |
 * + Runtime &    |                              +--------------+
 * | Models       |                              | Sessions     |
 * |              |                              | Agent        |
 * |              |                              | Explorer     |
 * +--------------+------------------------------+--------------+
 * | Console · Terminal? · Ports · Activity · Changes (bottom)   |
 * +------------------------------------------------------------+
 * </pre>
 *
 * The AI selection toolbar is the primary interaction entry point; the editor area
 * hosts opened files; all panels are resizable and the workbench persists the
 * layout.
 */
public final class DCodePerspectiveFactory implements IPerspectiveFactory {

    @Override
    public void createInitialLayout(IPageLayout layout) {
        String editorArea = layout.getEditorArea();
        layout.setEditorAreaVisible(true);

        layout.addView("eu.kalafatic.dcode.ui.views.workspace", IPageLayout.LEFT,
                0.22f, editorArea);
        layout.addView("eu.kalafatic.dcode.ui.views.runtimeexplorer", IPageLayout.BOTTOM,
                0.55f, "eu.kalafatic.dcode.ui.views.workspace");

        layout.addView("eu.kalafatic.dcode.ui.views.inference", IPageLayout.RIGHT,
                0.42f, editorArea);
        layout.addView("eu.kalafatic.dcode.ui.views.agentexplorer", IPageLayout.BOTTOM,
                0.45f, "eu.kalafatic.dcode.ui.views.inference");

        var bottom = layout.createFolder("eu.kalafatic.dcode.ui.bottom",
                IPageLayout.BOTTOM, 0.30f, editorArea);
        bottom.addView("eu.kalafatic.dcode.ui.views.console");
        bottom.addView("eu.kalafatic.dcode.ui.views.portmonitor");
        bottom.addView("eu.kalafatic.dcode.ui.views.activity");
        bottom.addView("eu.kalafatic.dcode.ui.views.changes");
        bottom.addPlaceholder("eu.kalafatic.dcode.ui.views.javatools");

        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.connection");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.providers");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.models");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.modelmanager");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.runtimes");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.directchat");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.javatools");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.agentexplorer");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.portmonitor");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.runtimeselector");
    }
}
