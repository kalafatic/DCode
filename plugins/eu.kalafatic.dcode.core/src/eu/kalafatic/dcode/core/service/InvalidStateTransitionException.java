package eu.kalafatic.dcode.core.service;

/**
 * Thrown when a session state transition is rejected by the session state machine.
 */
public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(String message) {
        super(message);
    }
}
