package eu.kalafatic.dcode.config.runtime;

import eu.kalafatic.dcode.config.provider.ProviderException;

/**
 * Consumer of streaming generation events.
 *
 * <p>Exactly one terminal callback ({@link #onComplete} or {@link #onError}) is
 * delivered per stream. Implementations must be safe to invoke from the adapter's
 * background thread.</p>
 */
public interface StreamListener {

    /** One text token (or chunk) of the generation. */
    void onToken(String text);

    /** Terminal: generation finished or was cancelled. */
    void onComplete(StreamResult result);

    /** Terminal: generation failed before completion. */
    void onError(ProviderException error);
}
