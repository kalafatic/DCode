package eu.kalafatic.dcode.config.provider;

/** Thrown when a provider operation fails (connection, parsing, configuration). */
public class ProviderException extends RuntimeException {

    public ProviderException(String message) {
        super(message);
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
