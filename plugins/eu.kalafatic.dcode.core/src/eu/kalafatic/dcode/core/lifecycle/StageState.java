package eu.kalafatic.dcode.core.lifecycle;

/** State of one lifecycle stage. */
public enum StageState {
    NOT_STARTED,
    STARTING,
    STARTED,
    STOPPING,
    STOPPED,
    FAILED
}
