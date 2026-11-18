package eu.kalafatic.dcode.config.credentials;

import java.util.Optional;
import java.util.Set;

/**
 * Credential vault contract: secrets are stored by key, retrieved by explicit call, and
 * never enumerated with their values. Implementations protect secrets at rest; the core
 * in-memory configuration service is replaced by the durable encrypted vault in WP5.7.
 */
public interface CredentialVault {

    void put(String key, String secret);

    Optional<String> get(String key);

    void remove(String key);

    boolean has(String key);

    /** Secret key names only, never values. */
    Set<String> keys();

    /** Returns a masked marker for display (never derived from the secret). */
    default String masked(String key) {
        return has(key) ? "***" : "";
    }
}
