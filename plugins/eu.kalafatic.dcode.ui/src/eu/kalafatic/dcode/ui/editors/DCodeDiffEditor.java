package eu.kalafatic.dcode.ui.editors;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.EditorPart;

/** Read-only diff editor with addition/deletion highlighting. */
public final class DCodeDiffEditor extends EditorPart {

    private SourceViewer viewer;

    @Override
    public void init(IEditorSite site, IEditorInput input) throws PartInitException {
        if (!(input instanceof DCodeDiffEditorInput)) {
            throw new PartInitException("Invalid input: DCodeDiffEditorInput expected");
        }
        setSite(site);
        setInput(input);
    }

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        viewer = new SourceViewer(root, null, SWT.V_SCROLL | SWT.H_SCROLL);
        viewer.getTextWidget().setLayoutData(new GridData(GridData.FILL_BOTH));
        viewer.getTextWidget().setFont(org.eclipse.jface.resource.JFaceResources
                .getFont(org.eclipse.jface.resource.JFaceResources.TEXT_FONT));

        DCodeDiffEditorInput input = (DCodeDiffEditorInput) getEditorInput();
        setPartName(input.title());
        viewer.setDocument(new Document(input.diff()));

        StyledText text = viewer.getTextWidget();
        text.addLineStyleListener(event -> {
            Color add = new Color(text.getDisplay(), new RGB(70, 120, 70));
            Color del = new Color(text.getDisplay(), new RGB(130, 70, 70));
            Color hunk = new Color(text.getDisplay(), new RGB(90, 120, 160));
            Color meta = new Color(text.getDisplay(), new RGB(110, 116, 126));
            Color color = null;
            if (event.lineText.startsWith("+") && !event.lineText.startsWith("+++")) {
                color = add;
            } else if (event.lineText.startsWith("-") && !event.lineText.startsWith("---")) {
                color = del;
            } else if (event.lineText.startsWith("@@")) {
                color = hunk;
            } else if (event.lineText.startsWith("diff ") || event.lineText.startsWith("index ")
                    || event.lineText.startsWith("+++") || event.lineText.startsWith("---")
                    || event.lineText.startsWith("new file") || event.lineText.startsWith(
                    "deleted file")) {
                color = meta;
            }
            if (color != null) {
                text.setStyleRange(new StyleRange(event.lineOffset,
                        event.lineText.length(), color, null));
            }
        });
    }

    @Override
    public boolean isDirty() {
        return false;
    }

    @Override
    public boolean isSaveAsAllowed() {
        return false;
    }

    @Override
    public void doSave(IProgressMonitor monitor) {
        // read-only diff
    }

    @Override
    public void doSaveAs() {
        // read-only diff
    }

    @Override
    public void setFocus() {
        viewer.getTextWidget().setFocus();
    }
}
