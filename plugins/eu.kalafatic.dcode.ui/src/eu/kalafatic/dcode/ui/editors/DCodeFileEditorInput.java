package eu.kalafatic.dcode.ui.editors;

import eu.kalafatic.dcode.core.model.Workspace;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IPersistableElement;

/**
 * Editor input for a backend-provided file. The path is a server-side workspace
 * path; the editor fetches content through the API (local and remote modes are
 * identical — remote paths are never assumed to exist locally).
 */
public final class DCodeFileEditorInput implements IEditorInput {

    private final Workspace workspace;
    private final String repositoryName;
    private final String path;

    public DCodeFileEditorInput(Workspace workspace, String repositoryName, String path) {
        this.workspace = workspace;
        this.repositoryName = repositoryName;
        this.path = path;
    }

    public Workspace workspace() {
        return workspace;
    }

    public String repositoryName() {
        return repositoryName;
    }

    public String path() {
        return path;
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
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    @Override
    public IPersistableElement getPersistable() {
        return null;
    }

    @Override
    public String getToolTipText() {
        return path + " (" + repositoryName + ")";
    }

    @Override
    public <T> T getAdapter(Class<T> adapter) {
        return null;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof DCodeFileEditorInput other)) {
            return false;
        }
        return workspace.id().equals(other.workspace.id()) && path.equals(other.path);
    }

    @Override
    public int hashCode() {
        return workspace.id().hashCode() * 31 + path.hashCode();
    }
}
