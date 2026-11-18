package eu.kalafatic.dcode.ui.editors;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IPersistableElement;

/** Input for a diff/review editor (session-scoped change set). */
public final class DCodeDiffEditorInput implements IEditorInput {

    private final String title;
    private final String diff;

    public DCodeDiffEditorInput(String title, String diff) {
        this.title = title;
        this.diff = diff;
    }

    public String title() {
        return title;
    }

    public String diff() {
        return diff;
    }

    @Override
    public boolean exists() {
        return true;
    }

    @Override
    public ImageDescriptor getImageDescriptor() {
        return null;
    }

    @Override
    public String getName() {
        return title;
    }

    @Override
    public IPersistableElement getPersistable() {
        return null;
    }

    @Override
    public String getToolTipText() {
        return title;
    }

    @Override
    public <T> T getAdapter(Class<T> adapter) {
        return null;
    }
}
