package eu.kalafatic.dcode.persistence.recovery;

import eu.kalafatic.dcode.core.log.DCodeLog;
import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.core.model.SessionState;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.core.service.WorkspaceService;

/**
 * Restart recovery for sessions.
 *
 * <p>Runtime execution state is separate from persisted state. After a restart,
 * sessions that were RUNNING or WAITING_FOR_APPROVAL while execution ran are transitioned
 * to FAILED with an explicit diagnostic — never blindly re-run. SUSPENDED sessions stay
 * suspended; terminal states stay terminal. (Crashes interrupt execution; there is no
 * runtime left to resume it.)</p>
 */
public final class SessionRecoveryService {

    private final SessionService sessionService;
    private final WorkspaceService workspaceService;

    public SessionRecoveryService(SessionService sessionService,
                                  WorkspaceService workspaceService) {
        this.sessionService = sessionService;
        this.workspaceService = workspaceService;
    }

    /** Runs the recovery pass over all persisted sessions. */
    public void recover() {
        int failed = 0;
        for (var workspace : workspaceService.listWorkspaces()) {
            for (Session session : sessionService.listSessions(workspace.id())) {
                if (session.getState() == SessionState.RUNNING
                        || session.getState() == SessionState.WAITING_FOR_APPROVAL) {
                    sessionService.appendMessage(session.getId(),
                            eu.kalafatic.dcode.core.model.MessageRole.SYSTEM,
                            "Runtime restarted while this session was in state "
                                    + session.getState() + "; execution state was not "
                                    + "recoverable and the session was marked FAILED.");
                    sessionService.transition(session.getId(), SessionState.FAILED);
                    failed++;
                    DCodeLog.info("recovery", "Session " + session.getId() + " was "
                            + session.getState() + " at restart -> FAILED");
                }
            }
        }
        DCodeLog.info("recovery", "Recovery pass complete (" + failed + " session(s) failed)");
    }
}
