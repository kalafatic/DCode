package eu.kalafatic.dcode.config.profile;

import java.util.Objects;

/**
 * Conversation profile: a named, reusable set of execution-context preferences.
 * Profiles are bound to sessions as dedicated SYSTEM envelopes
 * ({@code source=DCODE_CONVERSATION_PROFILE}) rendered by the agent loop into the
 * system prompt, so selecting a profile genuinely changes the execution context.
 */
public record ConversationProfile(
        String id,
        String name,
        String description,
        String systemInstructions,
        boolean builtin
) {
    public ConversationProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }

    public static ConversationProfile builtin(String id, String name,
            String description, String systemInstructions) {
        return new ConversationProfile(id, name, description, systemInstructions, true);
    }
}
