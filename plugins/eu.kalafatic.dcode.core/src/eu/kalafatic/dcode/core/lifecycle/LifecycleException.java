package eu.kalafatic.dcode.core.lifecycle;

/** Thrown when a lifecycle transition is invalid or a stage action fails. */
public class LifecycleException extends Exception {

    public LifecycleException(String message) {
        super(message);
    }

    public LifecycleException(String message, Throwable cause) {
        super(message, cause);
    }
}
