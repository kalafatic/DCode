package eu.kalafatic.dcode.ui;

import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPerspectiveFactory;

/** Default DCode perspective: IDE-style layout (explorer, center, right, bottom). */
public final class DCodePerspectiveFactory implements IPerspectiveFactory {

    @Override
    public void createInitialLayout(IPageLayout layout) {
        String editorArea = layout.getEditorArea();
        layout.setEditorAreaVisible(true);

        layout.addView("eu.kalafatic.dcode.ui.views.workspace", IPageLayout.LEFT,
                0.22f, editorArea);
        layout.addView("eu.kalafatic.dcode.ui.views.sessions", IPageLayout.BOTTOM,
                0.45f, "eu.kalafatic.dcode.ui.views.workspace");

        layout.addView("eu.kalafatic.dcode.ui.views.chat", IPageLayout.RIGHT,
                0.30f, editorArea);
        layout.addView("eu.kalafatic.dcode.ui.views.activity", IPageLayout.BOTTOM,
                0.50f, "eu.kalafatic.dcode.ui.views.chat");

        layout.addView("eu.kalafatic.dcode.ui.views.console", IPageLayout.BOTTOM,
                0.70f, editorArea);
        layout.addView("eu.kalafatic.dcode.ui.views.changes", IPageLayout.BOTTOM,
                0.50f, "eu.kalafatic.dcode.ui.views.console");

        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.javatools");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.providers");
        layout.addShowViewShortcut("eu.kalafatic.dcode.ui.views.connection");
    }
}
