package eu.kalafatic.dcode.java.tooling.jdt;

/** Thrown when the Java tooling cannot perform a requested operation. */
public class ToolingException extends RuntimeException {

    public ToolingException(String message) {
        super(message);
    }

    public ToolingException(String message, Throwable cause) {
        super(message, cause);
    }
}
