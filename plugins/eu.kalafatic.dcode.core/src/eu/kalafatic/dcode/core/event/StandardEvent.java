package eu.kalafatic.dcode.core.event;

import java.time.Instant;
import java.util.Objects;

/** Standard immutable event value. */
public record StandardEvent(
        DCodeEventType type,
        String sessionId,
        Instant timestamp,
        String dataJson
) implements DCodeEvent {

    public StandardEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(timestamp, "timestamp");
        dataJson = dataJson == null ? "" : dataJson;
    }

    public static StandardEvent of(DCodeEventType type, String sessionId, String dataJson) {
        return new StandardEvent(type, sessionId, Instant.now(), dataJson);
    }

    public static StandardEvent system(DCodeEventType type, String dataJson) {
        return new StandardEvent(type, null, Instant.now(), dataJson);
    }
}
