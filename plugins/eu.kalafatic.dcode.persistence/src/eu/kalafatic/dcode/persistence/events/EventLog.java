package eu.kalafatic.dcode.persistence.events;

import eu.kalafatic.dcode.core.event.DCodeEvent;
import eu.kalafatic.dcode.core.event.DCodeEventType;
import eu.kalafatic.dcode.core.event.EventBus;
import eu.kalafatic.dcode.persistence.db.SqliteDatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Durable event log: subscribes to the event bus, assigns a per-session sequence and
 * persists events for replay. Sequences survive restarts; replay is cursor-based
 * ({@code afterSequence}), matching the family TEIP event contract.
 */
public final class EventLog implements AutoCloseable {

    private final SqliteDatabaseManager database;
    private final ConcurrentHashMap<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final EventBus.Subscription subscription;

    public EventLog(SqliteDatabaseManager database, EventBus eventBus) {
        this.database = Objects.requireNonNull(database, "database");
        // MESSAGE_PARTIAL tokens are transient presentation deltas; the authoritative
        // text is durably logged through MESSAGE_APPENDED.
        this.subscription = eventBus.subscribe(e -> e.type() != DCodeEventType.MESSAGE_PARTIAL,
                this::onEvent);
    }

    private void onEvent(DCodeEvent event) {
        String sessionId = event.sessionId();
        AtomicLong counter = sequences.computeIfAbsent(sessionId != null ? sessionId : "",
                k -> new AtomicLong(maxSequence(sessionId)));
        long sequence = counter.incrementAndGet();
        String id = UUID.randomUUID().toString();
        database.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO events(id, session_id, type, sequence, timestamp, data_json) "
                            + "VALUES (?,?,?,?,?,?)")) {
                statement.setString(1, id);
                statement.setString(2, sessionId);
                statement.setString(3, event.type().name());
                statement.setLong(4, sequence);
                statement.setString(5, event.timestamp().toString());
                statement.setString(6, event.dataJson());
                statement.executeUpdate();
            }
        });
    }

    /** Replays events of one session after the given sequence (inclusive of next). */
    public List<PersistedEvent> replay(String sessionId, long afterSequence, int limit) {
        return database.query(connection -> {
            List<PersistedEvent> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM events WHERE session_id = ? AND sequence > ? "
                            + "ORDER BY sequence ASC LIMIT ?")) {
                statement.setString(1, sessionId);
                statement.setLong(2, afterSequence);
                statement.setInt(3, Math.min(Math.max(limit, 1), 500));
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        result.add(new PersistedEvent(rs.getString("id"),
                                rs.getString("session_id"), rs.getString("type"),
                                rs.getLong("sequence"), Instant.parse(rs.getString("timestamp")),
                                rs.getString("data_json")));
                    }
                }
            }
            return result;
        });
    }

    private long maxSequence(String sessionId) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COALESCE(MAX(sequence), 0) FROM events WHERE session_id = ?")) {
                statement.setString(1, sessionId);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    @Override
    public void close() {
        subscription.unsubscribe();
    }

    /** One persisted event. */
    public record PersistedEvent(String id, String sessionId, String type, long sequence,
                                 Instant timestamp, String dataJson) {
    }
}
