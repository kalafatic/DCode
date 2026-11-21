package eu.kalafatic.dcode.ui.views;

import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;

/**
 * Session lifecycle actions shared by the session views: open, rename and delete
 * use the same backend operations as the Web UI (POST /rename, DELETE) — there is
 * one implementation per operation for every surface that exposes it.
 */
final class SessionActions {

    private SessionActions() {
    }

    /** Makes the session the active session and starts its SSE stream. */
    static void open(Session session) {
        RuntimeConnection.instance().setActiveSession(session);
        Ui.connectSessionStream(session);
        Ui.log("session active: " + session.getId() + " (" + session.getState() + ")");
    }

    /** Renames a session (same operation as the Web UI rename). */
    static void rename(Shell shell, Session session, Runnable afterChange) {
        InputDialog dialog = new InputDialog(shell, "Rename session",
                "New title for " + session.getTitle() + ":", session.getTitle(), null);
        if (dialog.open() != org.eclipse.jface.dialogs.Dialog.OK) {
            return;
        }
        String title = dialog.getValue() == null ? "" : dialog.getValue().trim();
        if (title.isEmpty()) {
            return;
        }
        Ui.run(RuntimeConnection.instance().api().renameSession(session.getId(), title),
                json -> {
                    Ui.log("info", "session renamed: " + session.getId());
                    if (afterChange != null) {
                        Ui.async(afterChange);
                    }
                }, "rename session");
    }

    /** Deletes a session after confirmation (same operation as the Web UI delete). */
    static void delete(Shell shell, Session session, Runnable afterChange) {
        boolean confirmed = MessageDialog.openConfirm(shell, "Delete session",
                "Delete the session \"" + session.getTitle()
                        + "\"?\nIts message history is removed as well.");
        if (!confirmed) {
            return;
        }
        Ui.run(RuntimeConnection.instance().api().deleteSession(session.getId()),
                json -> {
                    Ui.log("info", "session deleted: " + session.getId());
                    if (afterChange != null) {
                        Ui.async(afterChange);
                    }
                }, "delete session");
    }
}
