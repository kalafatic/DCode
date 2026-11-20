package eu.kalafatic.dcode.config.runtime;

import eu.kalafatic.dcode.config.provider.ChatResponse;
import eu.kalafatic.dcode.config.provider.ProviderException;

import java.util.Objects;

/**
 * Result of a streaming generation.
 *
 * @param response aggregated completion result (empty content when cancelled)
 * @param cancelled whether generation was cancelled before completion
 * @param error the failure when the stream terminated with an error (null otherwise)
 */
public record StreamResult(
        ChatResponse response,
        boolean cancelled,
        ProviderException error
) {
    public StreamResult {
        Objects.requireNonNull(response, "response");
    }

    public static StreamResult completed(ChatResponse response) {
        return new StreamResult(response, false, null);
    }

    public static StreamResult cancelled(String content) {
        return new StreamResult(new ChatResponse(content == null ? "" : content,
                "cancelled", 0, 0), true, null);
    }

    public static StreamResult failed(ProviderException error) {
        return new StreamResult(new ChatResponse("", "error", 0, 0), false,
                Objects.requireNonNull(error, "error"));
    }
}
