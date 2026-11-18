package eu.kalafatic.dcode.config.provider;

import java.util.List;
import java.util.Objects;

/** A completion request addressed to one provider. */
public record ChatRequest(
        String model,
        List<ChatMessage> messages,
        double temperature,
        int maxTokens
) {
    public ChatRequest {
        Objects.requireNonNull(model, "model");
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (maxTokens < 0) {
            maxTokens = 0;
        }
    }
}
