package eu.kalafatic.dcode.core.model;

/**
 * Execution state of an agent run, tool call or delegated execution.
 *
 * <p>Values mirror the external execution states of the family TEIP 1.1 protocol
 * (QUEUED, RUNNING, WAITING, COMPLETED, FAILED, CANCELLED) so that DCode-internal state can
 * be published to family participants without translation.</p>
 */
public enum ExecutionState {
    QUEUED,
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
