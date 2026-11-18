package eu.kalafatic.dcode.ui;

import eu.kalafatic.dcode.client.DCodeSseClient;
import eu.kalafatic.dcode.core.model.Session;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Shared UI helpers: display marshalling, console sink, view refresh fan-out and the
 * workbench-level SSE wiring (events are processed off the UI thread and dispatched
 * to the display; duplicate ids are dropped by the client).
 */
public final class Ui {

    /** Listener invoked with the newly active session (on the UI thread). */
    public interface SessionListener {
        void sessionChanged(Session session);
    }

    private static final List<SessionListener> SESSION_LISTENERS = new CopyOnWriteArrayList<>();
    private static final DCodeSseClient.Listener SSE_LISTENER = new DCodeSseClient.Listener() {
        @Override
        public void onEvent(DCodeSseClient.SseEvent event) {
            Display.getDefault().asyncExec(() -> {
                logEvent(event.type(), event.data());
                refreshSessionViews();
            });
        }

        @Override
        public void onStatus(DCodeSseClient.Status status, String detail) {
            Display.getDefault().asyncExec(() -> {
                log("Stream " + status.name().toLowerCase()
                        + (detail.isEmpty() ? "" : " (" + detail + ")"));
            });
        }

        @Override
        public void onReconnected() {
            Display.getDefault().asyncExec(() -> {
                log("Stream reconnected; re-fetching authoritative state…");
                refreshSessionViews();
            });
        }
    };

    private Ui() {
    }

    public static void async(Runnable runnable) {
        Display display = Display.getCurrent();
        if (display != null) {
            runnable.run();
        } else {
            Display.getDefault().asyncExec(runnable);
        }
    }

    public static void log(String kind, String message) {
        async(() -> {
            IWorkbenchPage page = activePage();
            if (page != null) {
                IViewReference reference = page.findViewReference(
                        "eu.kalafatic.dcode.ui.views.console");
                if (reference != null && reference.getView(false) instanceof
                        eu.kalafatic.dcode.ui.views.ConsoleView console) {
                    console.append(kind, message);
                    return;
                }
            }
            System.out.println("[DCode:" + kind + "] " + message);
        });
    }

    public static void log(String message) {
        async(() -> {
            IWorkbenchPage page = activePage();
            if (page != null) {
                IViewReference reference = page.findViewReference(
                        "eu.kalafatic.dcode.ui.views.console");
                if (reference != null && reference.getView(false) instanceof
                        eu.kalafatic.dcode.ui.views.ConsoleView console) {
                    console.append("system", message);
                    return;
                }
            }
            System.out.println("[DCode] " + message);
        });
    }

    public static void logEvent(String type, String data) {
        async(() -> {
            IWorkbenchPage page = activePage();
            if (page != null) {
                IViewReference reference = page.findViewReference(
                        "eu.kalafatic.dcode.ui.views.console");
                if (reference != null && reference.getView(false) instanceof
                        eu.kalafatic.dcode.ui.views.ConsoleView console) {
                    console.appendEvent(type, data);
                    return;
                }
            }
            System.out.println("[DCode:" + type + "] " + data);
        });
    }

    /** Opens/activates the per-session SSE stream for the active session. */
    public static void connectSessionStream(Session session) {
        RuntimeConnection.instance().sse().connect(session.getId(), SSE_LISTENER);
    }

    /** Refreshes all DCode views (used after reconnect / connection changes). */
    public static void refreshAll() {
        async(() -> {
            IWorkbenchPage page = activePage();
            if (page == null) {
                return;
            }
            for (IViewReference reference : page.getViewReferences()) {
                if (reference.getView(false) instanceof Refreshable refreshable) {
                    refreshable.refreshContent();
                }
            }
        });
    }

    public static void refreshSessionViews() {
        async(() -> {
            IWorkbenchPage page = activePage();
            if (page == null) {
                return;
            }
            for (String id : new String[]{
                    "eu.kalafatic.dcode.ui.views.sessions",
                    "eu.kalafatic.dcode.ui.views.chat",
                    "eu.kalafatic.dcode.ui.views.changes",
                    "eu.kalafatic.dcode.ui.views.activity"}) {
                IViewReference reference = page.findViewReference(id);
                if (reference != null && reference.getView(false) instanceof Refreshable refreshable) {
                    refreshable.refreshContent();
                }
            }
        });
    }

    public static void broadcastSessionChanged(Session session) {
        async(() -> {
            for (SessionListener listener : SESSION_LISTENERS) {
                listener.sessionChanged(session);
            }
        });
    }

    public static void addSessionListener(SessionListener listener) {
        SESSION_LISTENERS.add(listener);
    }

    public static void removeSessionListener(SessionListener listener) {
        SESSION_LISTENERS.remove(listener);
    }

    public static IWorkbenchPage activePage() {
        if (PlatformUI.getWorkbench() == null
                || PlatformUI.getWorkbench().getActiveWorkbenchWindow() == null) {
            return null;
        }
        return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
    }

    /** Marker for views that re-load authoritative backend state on demand. */
    public interface Refreshable {
        void refreshContent();
    }

    /** Runs an async API call with a loading callback and error reporting. */
    public static <T> void run(java.util.concurrent.CompletableFuture<T> future,
                               Consumer<T> onSuccess, String errorContext) {
        future.whenComplete((result, throwable) -> async(() -> {
            if (throwable != null) {
                log("error", errorContext + ": " + throwable.getMessage());
                return;
            }
            onSuccess.accept(result);
        }));
    }
}
