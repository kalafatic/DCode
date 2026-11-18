package eu.kalafatic.dcode.config.provider;

/** A completed assistant response. */
public record ChatResponse(
        String content,
        String finishReason,
        int promptTokens,
        int completionTokens
) {
    public ChatResponse {
        content = content == null ? "" : content;
    }
}
