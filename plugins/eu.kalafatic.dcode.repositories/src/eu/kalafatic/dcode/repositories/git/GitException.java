package eu.kalafatic.dcode.repositories.git;

/** Thrown when a Git operation fails or is invoked against an invalid repository. */
public class GitException extends RuntimeException {

    public GitException(String message) {
        super(message);
    }

    public GitException(String message, Throwable cause) {
        super(message, cause);
    }
}
