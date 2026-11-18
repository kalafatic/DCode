package eu.kalafatic.dcode.config.provider;

import eu.kalafatic.dcode.core.model.MessageRole;

import java.util.Objects;

/** One chat message in a completion request. */
public record ChatMessage(
        MessageRole role,
        String content
) {
    public ChatMessage {
        Objects.requireNonNull(role, "role");
        content = content == null ? "" : content;
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(MessageRole.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(MessageRole.ASSISTANT, content);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(MessageRole.SYSTEM, content);
    }
}
