package eu.kalafatic.dcode.tests.rcp;

import eu.kalafatic.dcode.ui.editors.DCodeMultiPageEditor;
import eu.kalafatic.dcode.ui.editors.DCodeMultiPageEditorInput;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchWindow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared assertions for the automatically-opened harness editor (used by the single
 * workbench runtime smoke test — the workbench can only be created once per OSGi
 * process, so these checks must not live in a second {@code @Test} method).
 */
final class RcpMultiPageEditorChecks {

    private RcpMultiPageEditorChecks() {
    }

    /**
     * The startup flow (WorkbenchAdvisor.postStartup) must have opened the main
     * editor automatically. Verifies: editor present without manual opening, both
     * pages, the initial page, the SWT Browser loading the real web application,
     * and single-instance semantics (reopening returns the same part).
     */
    static DCodeMultiPageEditor waitForAutoOpenAndAssert(IWorkbench workbench,
                                                         IWorkbenchWindow window)
            throws Exception {
        // 1. The editor must be present WITHOUT an explicit openEditor call.
        long deadline = System.currentTimeMillis() + 30000;
        DCodeMultiPageEditor editor = null;
        while (System.currentTimeMillis() < deadline) {
            final DCodeMultiPageEditor[] found = {null};
            workbench.getDisplay().syncExec(() -> {
                var part = window.getActivePage().findEditor(
                        new DCodeMultiPageEditorInput());
                if (part instanceof DCodeMultiPageEditor multi) {
                    found[0] = multi;
                }
            });
            editor = found[0];
            if (editor != null) {
                break;
            }
            Thread.sleep(200);
        }
        assertNotNull(editor, "the main harness editor must open automatically "
                + "at startup (postStartup) — no manual openEditor call");

        // 2. Pages: Web (initial) + Settings.
        final DCodeMultiPageEditor finalEditor = editor;
        workbench.getDisplay().syncExec(() -> {
            assertEquals(2, finalEditor.pageCount(), "Web + Settings pages");
            assertEquals("Web", finalEditor.pageTitle(0), "initial page must be Web");
            assertEquals("Settings", finalEditor.pageTitle(1));
        });

        // 3. The SWT Browser must load the actual web application (readiness wait).
        long webDeadline = System.currentTimeMillis() + 60000;
        while (!finalEditor.isWebLoaded() && System.currentTimeMillis() < webDeadline) {
            Thread.sleep(500);
        }
        if (!finalEditor.isWebLoaded()) {
            // The readiness window may have expired before the server came up:
            // manual reload is part of the contract and must recover.
            workbench.getDisplay().syncExec(finalEditor::reloadWeb);
            webDeadline = System.currentTimeMillis() + 60000;
            while (!finalEditor.isWebLoaded()
                    && System.currentTimeMillis() < webDeadline) {
                Thread.sleep(500);
            }
        }
        assertTrue(finalEditor.isWebLoaded(),
                "the SWT Browser must load the DCode web application — browser "
                        + "created: " + finalEditor.isWebBrowserCreated()
                        + ", status: " + finalEditor.webStatusText()
                        + ", last endpoint: " + finalEditor.lastEndpoint());
        String webUrl = finalEditor.webUrl();
        assertNotNull(webUrl, "resolved web URL must be recorded");
        assertTrue(webUrl.startsWith("http://127.0.0.1:"),
                "web application URL must target the embedded server: " + webUrl);
        assertTrue(webUrl.endsWith("/"), "web application URL must be the root: "
                + webUrl);

        // 4. Single instance: reopening with the same stable input returns the
        //    same part — no duplicate editors.
        final DCodeMultiPageEditor[] reopened = {null};
        workbench.getDisplay().syncExec(() -> {
            try {
                reopened[0] = (DCodeMultiPageEditor) window.getActivePage()
                        .openEditor(new DCodeMultiPageEditorInput(),
                                DCodeMultiPageEditor.EDITOR_ID);
            } catch (org.eclipse.ui.PartInitException e) {
                throw new RuntimeException(e);
            }
        });
        assertSame(finalEditor, reopened[0],
                "reopening the editor input must reuse the single editor instance");

        // 5. The Settings tab must load its configuration from the backend.
        workbench.getDisplay().syncExec(() -> finalEditor.showPage(1));
        long settingsDeadline = System.currentTimeMillis() + 30000;
        while (!finalEditor.isSettingsReady()
                && System.currentTimeMillis() < settingsDeadline) {
            Thread.sleep(300);
        }
        assertTrue(finalEditor.isSettingsReady(),
                "the Settings tab must load all settings from the backend — "
                        + "diag: " + finalEditor.settingsDiagnostics());
        workbench.getDisplay().syncExec(() -> finalEditor.showPage(0));

        // 6. Reset Perspective must restore the layout panels and the main editor
        //    with its pages.
        workbench.getDisplay().syncExec(() -> window.getActivePage().resetPerspective());
        DCodeMultiPageEditor restored = null;
        long resetDeadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < resetDeadline) {
            final DCodeMultiPageEditor[] found = {null};
            workbench.getDisplay().syncExec(() -> {
                var part = window.getActivePage().findEditor(
                        new DCodeMultiPageEditorInput());
                if (part instanceof DCodeMultiPageEditor multi) {
                    found[0] = multi;
                }
            });
            restored = found[0];
            if (restored != null) {
                break;
            }
            Thread.sleep(300);
        }
        assertNotNull(restored,
                "the main editor must be present after Reset Perspective");
        final DCodeMultiPageEditor finalRestored = restored;
        workbench.getDisplay().syncExec(() -> {
            assertEquals(2, finalRestored.pageCount(),
                    "Web + Settings pages after reset");
        });
        long restoredWebDeadline = System.currentTimeMillis() + 30000;
        while (!finalRestored.isWebLoaded()
                && System.currentTimeMillis() < restoredWebDeadline) {
            Thread.sleep(300);
        }
        assertTrue(finalRestored.isWebLoaded(),
                "the Web tab must be loaded after Reset Perspective");
        return finalEditor;
    }
}
