package eu.kalafatic.dcode.config.profile;

import java.util.List;
import java.util.Optional;

/**
 * Conversation profile registry contract: built-in and custom conversation profiles
 * plus the default selection. One registry per DCode runtime; new sessions bind the
 * selected profile into their execution context.
 */
public interface ProfileRegistry {

    /** All profiles (built-ins first). */
    List<ConversationProfile> profiles();

    Optional<ConversationProfile> find(String id);

    /** Creates or replaces a custom profile (built-ins are immutable). */
    ConversationProfile register(ConversationProfile profile);

    /** Removes a custom profile (built-ins are immutable). */
    void unregister(String id);

    Optional<ConversationProfile> defaultProfile();

    void setDefaultProfile(String id);
}
