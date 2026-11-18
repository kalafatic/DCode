package eu.kalafatic.dcode.core.model;

import java.time.Instant;

/** One conversation message of a session. */
public record Message(
        String id,
        String sessionId,
        MessageRole role,
        String content,
        long sequence,
        Instant timestamp
) {
    public Message {
        if (content == null) {
            content = "";
        }
    }
}
