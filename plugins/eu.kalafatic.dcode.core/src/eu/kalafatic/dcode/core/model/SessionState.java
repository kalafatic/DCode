package eu.kalafatic.dcode.core.model;

/**
 * Lifecycle state of a DCode coding session.
 *
 * <p>State transitions are validated exclusively by
 * {@link SessionState#isValidTransition(SessionState, SessionState)}; services must never
 * mutate session state without going through that validation.</p>
 */
public enum SessionState {

    /** Session created, never started. */
    CREATED,

    /** Session is actively executing. */
    RUNNING,

    /** Explicitly suspended by the user; resumable. */
    SUSPENDED,

    /** Execution finished; a human decision (approve/reject/commit) is pending. */
    WAITING_FOR_APPROVAL,

    /** Terminal: completed successfully. */
    COMPLETED,

    /** Terminal: failed; diagnostics are recorded in the session events. */
    FAILED,

    /** Terminal: cancelled by user or policy. */
    CANCELLED,

    /** Terminal: archived for retention. */
    ARCHIVED;

    /**
     * Returns true when the transition {@code from -> to} is permitted.
     */
    public static boolean isValidTransition(SessionState from, SessionState to) {
        if (from == to) {
            return false;
        }
        return switch (from) {
            case CREATED -> to == SessionState.RUNNING || to == SessionState.CANCELLED
                    || to == SessionState.ARCHIVED || to == SessionState.FAILED;
            case RUNNING -> to == SessionState.SUSPENDED || to == SessionState.WAITING_FOR_APPROVAL
                    || to == SessionState.COMPLETED || to == SessionState.FAILED
                    || to == SessionState.CANCELLED;
            case SUSPENDED -> to == SessionState.RUNNING || to == SessionState.CANCELLED
                    || to == SessionState.FAILED || to == SessionState.ARCHIVED;
            case WAITING_FOR_APPROVAL -> to == SessionState.COMPLETED || to == SessionState.RUNNING
                    || to == SessionState.FAILED || to == SessionState.CANCELLED
                    || to == SessionState.ARCHIVED;
            case COMPLETED, FAILED, CANCELLED -> to == SessionState.ARCHIVED;
            case ARCHIVED -> false;
        };
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == ARCHIVED;
    }

    public boolean isActive() {
        return this == RUNNING || this == WAITING_FOR_APPROVAL;
    }
}
