package eu.kalafatic.dcode.config.runtime;

import java.time.Instant;
import java.util.Objects;

/**
 * Last known connectivity/health state of one runtime registration.
 *
 * @param reachable the endpoint answered the last probe
 * @param healthy the endpoint reported a healthy state
 * @param message human-readable detail of the last probe
 * @param checkedAt when the last probe completed ({@link Instant#EPOCH} when never probed)
 */
public record RuntimeStatus(
        boolean reachable,
        boolean healthy,
        String message,
        Instant checkedAt
) {
    public RuntimeStatus {
        message = message == null ? "" : message;
        checkedAt = Objects.requireNonNullElse(checkedAt, Instant.EPOCH);
    }

    public static RuntimeStatus unknown() {
        return new RuntimeStatus(false, false, "not tested", Instant.EPOCH);
    }

    public static RuntimeStatus ok(String message) {
        return new RuntimeStatus(true, true, message, Instant.now());
    }

    public static RuntimeStatus reachable(String message) {
        return new RuntimeStatus(true, false, message, Instant.now());
    }

    public static RuntimeStatus failed(String message) {
        return new RuntimeStatus(false, false, message, Instant.now());
    }
}
