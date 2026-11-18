package eu.kalafatic.dcode.persistence.db;

import eu.kalafatic.dcode.core.log.DCodeLog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Forward-only, transactional schema migrations (family convention). Applied
 * sequentially inside one transaction per version; the {@code schema_version} table is
 * the single source of truth.
 */
public final class SchemaMigrationService {

    private final SqliteDatabaseManager database;

    public SchemaMigrationService(SqliteDatabaseManager database) {
        this.database = database;
    }

    /** Applies all pending migrations. Idempotent. */
    public void migrate() {
        int current = currentVersion();
        for (Migration migration : MIGRATIONS) {
            if (migration.version() <= current) {
                continue;
            }
            DCodeLog.info("persistence", "Applying database migration V" + migration.version());
            final Migration m = migration;
            database.inTransaction(connection -> {
                m.apply(connection);
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO schema_version(version, applied_at) VALUES (?, ?)")) {
                    statement.setInt(1, m.version());
                    statement.setString(2, Instant.now().toString());
                    statement.executeUpdate();
                }
            });
        }
    }

    /** Current schema version (0 for a fresh database). */
    public int currentVersion() {
        return database.query(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS schema_version("
                        + "version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT MAX(version) FROM schema_version")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    private interface Migration {
        int version();

        void apply(Connection connection) throws SQLException;
    }

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration() {
                @Override
                public int version() {
                    return 1;
                }

                @Override
                public void apply(Connection connection) throws SQLException {
                    try (Statement s = connection.createStatement()) {
                        s.execute("CREATE TABLE workspaces("
                                + "id TEXT PRIMARY KEY,"
                                + "name TEXT NOT NULL,"
                                + "mode TEXT NOT NULL,"
                                + "root_path TEXT,"
                                + "created_at TEXT NOT NULL)");
                        s.execute("CREATE TABLE repositories("
                                + "id TEXT PRIMARY KEY,"
                                + "workspace_id TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,"
                                + "name TEXT NOT NULL,"
                                + "local_path TEXT NOT NULL,"
                                + "remote_url TEXT,"
                                + "branch TEXT,"
                                + "access_policy TEXT NOT NULL,"
                                + "added_at TEXT NOT NULL)");
                        s.execute("CREATE TABLE sessions("
                                + "id TEXT PRIMARY KEY,"
                                + "title TEXT NOT NULL,"
                                + "workspace_id TEXT NOT NULL,"
                                + "state TEXT NOT NULL,"
                                + "provider_id TEXT,"
                                + "model_id TEXT,"
                                + "agent_role TEXT,"
                                + "created_at TEXT NOT NULL,"
                                + "updated_at TEXT NOT NULL,"
                                + "metadata_json TEXT NOT NULL DEFAULT '{}')");
                        s.execute("CREATE TABLE messages("
                                + "id TEXT PRIMARY KEY,"
                                + "session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,"
                                + "role TEXT NOT NULL,"
                                + "content TEXT NOT NULL,"
                                + "sequence INTEGER NOT NULL,"
                                + "timestamp TEXT NOT NULL)");
                        s.execute("CREATE INDEX idx_messages_session ON messages(session_id, sequence)");
                        s.execute("CREATE TABLE tool_calls("
                                + "id TEXT PRIMARY KEY,"
                                + "session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,"
                                + "agent_run_id TEXT,"
                                + "tool_name TEXT NOT NULL,"
                                + "arguments_json TEXT NOT NULL,"
                                + "result_json TEXT,"
                                + "state TEXT NOT NULL,"
                                + "error_message TEXT,"
                                + "started_at TEXT NOT NULL,"
                                + "completed_at TEXT)");
                        s.execute("CREATE INDEX idx_tool_calls_session ON tool_calls(session_id)");
                        s.execute("CREATE TABLE agent_runs("
                                + "id TEXT PRIMARY KEY,"
                                + "session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,"
                                + "role TEXT NOT NULL,"
                                + "provider_id TEXT,"
                                + "model_id TEXT,"
                                + "state TEXT NOT NULL,"
                                + "summary TEXT,"
                                + "started_at TEXT NOT NULL,"
                                + "completed_at TEXT)");
                        s.execute("CREATE TABLE events("
                                + "id TEXT PRIMARY KEY,"
                                + "session_id TEXT,"
                                + "type TEXT NOT NULL,"
                                + "sequence INTEGER NOT NULL,"
                                + "timestamp TEXT NOT NULL,"
                                + "data_json TEXT NOT NULL)");
                        s.execute("CREATE INDEX idx_events_session ON events(session_id, sequence)");
                        s.execute("CREATE TABLE change_sets("
                                + "id TEXT PRIMARY KEY,"
                                + "session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,"
                                + "repository_id TEXT NOT NULL,"
                                + "summary TEXT,"
                                + "created_at TEXT NOT NULL)");
                        s.execute("CREATE TABLE change_files("
                                + "change_set_id TEXT NOT NULL REFERENCES change_sets(id) ON DELETE CASCADE,"
                                + "path TEXT NOT NULL,"
                                + "status TEXT NOT NULL)");
                        s.execute("CREATE TABLE reviews("
                                + "id TEXT PRIMARY KEY,"
                                + "session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,"
                                + "change_set_id TEXT NOT NULL,"
                                + "status TEXT NOT NULL,"
                                + "approver_id TEXT,"
                                + "comment TEXT,"
                                + "created_at TEXT NOT NULL,"
                                + "decided_at TEXT)");
                    }
                }
            });
}
