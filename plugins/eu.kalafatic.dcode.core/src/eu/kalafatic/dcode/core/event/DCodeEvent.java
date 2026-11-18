package eu.kalafatic.dcode.core.event;

import java.time.Instant;

/**
 * One event on the DCode event bus.
 *
 * <p>Events are plain, serialization-neutral values. The payload is opaque JSON text;
 * JSON encoding/decoding is the responsibility of persistence and server bundles, keeping
 * the core free of JSON library dependencies.</p>
 */
public interface DCodeEvent {

    DCodeEventType type();

    /** Owning session, or {@code null} for workspace/system events. */
    String sessionId();

    Instant timestamp();

    /** Opaque JSON payload (may be empty). */
    String dataJson();
}
