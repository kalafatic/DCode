package eu.kalafatic.dcode.persistence.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * SQLite connection manager: single database file, WAL journal, foreign keys, busy
 * timeout. Connections are short-lived; no connection is held across long operations.
 */
public final class SqliteDatabaseManager implements AutoCloseable {

    static {
        // In OSGi the JDBC DriverManager service-loader lookup does not work; the
        // sqlite driver must be loaded explicitly.
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final String url;

    public SqliteDatabaseManager(java.nio.file.Path databaseFile) {
        Objects.requireNonNull(databaseFile, "databaseFile");
        this.url = "jdbc:sqlite:" + databaseFile.toAbsolutePath();
    }

    /** Opens a new connection with the standard pragmas applied. */
    public Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    /**
     * Runs a single write transaction. Commits on success, rolls back on failure.
     * Callers must not hold the connection beyond the operation.
     */
    public void inTransaction(SqlRunnable operation) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                operation.run(connection);
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Database transaction failed", e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database access failed", e);
        }
    }

    /** Runs a single read operation on a fresh connection. */
    public <T> T query(SqlFunction<T> operation) {
        try (Connection connection = open()) {
            return operation.apply(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("Database query failed", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Database query failed", e);
        }
    }

    @FunctionalInterface
    public interface SqlRunnable {
        void run(Connection connection) throws Exception;
    }

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws Exception;
    }

    @Override
    public void close() {
        // Connections are created per operation; nothing to close globally.
    }
}
