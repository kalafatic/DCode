package eu.kalafatic.dcode.persistence.service;

import eu.kalafatic.dcode.core.event.DCodeEventType;
import eu.kalafatic.dcode.core.event.EventBus;
import eu.kalafatic.dcode.core.event.StandardEvent;
import eu.kalafatic.dcode.core.model.RepositoryAccessPolicy;
import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.core.model.WorkspaceMode;
import eu.kalafatic.dcode.core.service.WorkspaceService;
import eu.kalafatic.dcode.persistence.db.SqliteDatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite-backed workspace service (WP5.7): workspaces and repository registry entries
 * persist across restarts. Repository worktree operations remain with the repository
 * engine; this service owns registry metadata only.
 */
public final class SqliteWorkspaceService implements WorkspaceService {

    private final SqliteDatabaseManager database;
    private final EventBus eventBus;

    public SqliteWorkspaceService(SqliteDatabaseManager database, EventBus eventBus) {
        this.database = Objects.requireNonNull(database, "database");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

    @Override
    public Workspace createWorkspace(String name, WorkspaceMode mode, String rootPath) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mode, "mode");
        String id = UUID.randomUUID().toString();
        Workspace workspace = new Workspace(id, name, mode, rootPath, List.of(), Instant.now());
        database.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO workspaces(id, name, mode, root_path, created_at) "
                            + "VALUES (?,?,?,?,?)")) {
                statement.setString(1, id);
                statement.setString(2, name);
                statement.setString(3, mode.name());
                statement.setString(4, rootPath);
                statement.setString(5, workspace.createdAt().toString());
                statement.executeUpdate();
            }
        });
        eventBus.publish(StandardEvent.system(DCodeEventType.WORKSPACE_CREATED,
                "{\"workspaceId\":\"" + id + "\",\"name\":\"" + escape(name) + "\"}"));
        return workspace;
    }

    @Override
    public Optional<Workspace> getWorkspace(String workspaceId) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM workspaces WHERE id = ?")) {
                statement.setString(1, workspaceId);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<Workspace>empty();
                    }
                    return Optional.of(new Workspace(
                            rs.getString("id"),
                            rs.getString("name"),
                            WorkspaceMode.valueOf(rs.getString("mode")),
                            rs.getString("root_path"),
                            listRepositoriesInternal(connection, workspaceId),
                            Instant.parse(rs.getString("created_at"))));
                }
            }
        });
    }

    @Override
    public List<Workspace> listWorkspaces() {
        return database.query(connection -> {
            List<Workspace> result = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT * FROM workspaces ORDER BY created_at ASC")) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    result.add(new Workspace(id, rs.getString("name"),
                            WorkspaceMode.valueOf(rs.getString("mode")),
                            rs.getString("root_path"),
                            listRepositoriesInternal(connection, id),
                            Instant.parse(rs.getString("created_at"))));
                }
            }
            return result;
        });
    }

    @Override
    public Workspace addRepository(String workspaceId, RepositoryRef repository) {
        Objects.requireNonNull(repository, "repository");
        database.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM repositories WHERE workspace_id = ? AND name = ?")) {
                statement.setString(1, workspaceId);
                statement.setString(2, repository.name());
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        throw new IllegalArgumentException("Workspace already contains a "
                                + "repository named '" + repository.name() + "'");
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO repositories(id, workspace_id, name, local_path, remote_url,"
                            + " branch, access_policy, added_at) VALUES (?,?,?,?,?,?,?,?)")) {
                statement.setString(1, repository.id());
                statement.setString(2, workspaceId);
                statement.setString(3, repository.name());
                statement.setString(4, repository.localPath());
                statement.setString(5, repository.remoteUrl());
                statement.setString(6, repository.branch());
                statement.setString(7, repository.accessPolicy().name());
                statement.setString(8, repository.addedAt() != null
                        ? repository.addedAt().toString() : Instant.now().toString());
                statement.executeUpdate();
            }
        });
        eventBus.publish(StandardEvent.system(DCodeEventType.REPOSITORY_ADDED,
                "{\"workspaceId\":\"" + workspaceId + "\",\"repositoryId\":\""
                        + repository.id() + "\"}"));
        return getWorkspace(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown workspace: " + workspaceId));
    }

    @Override
    public Workspace removeRepository(String workspaceId, String repositoryId) {
        database.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM repositories WHERE workspace_id = ? AND id = ?")) {
                statement.setString(1, workspaceId);
                statement.setString(2, repositoryId);
                if (statement.executeUpdate() == 0) {
                    throw new IllegalArgumentException("No repository with id " + repositoryId
                            + " in workspace " + workspaceId);
                }
            }
        });
        eventBus.publish(StandardEvent.system(DCodeEventType.REPOSITORY_REMOVED,
                "{\"workspaceId\":\"" + workspaceId + "\",\"repositoryId\":\""
                        + repositoryId + "\"}"));
        return getWorkspace(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown workspace: " + workspaceId));
    }

    @Override
    public List<RepositoryRef> listRepositories(String workspaceId) {
        return getWorkspace(workspaceId)
                .map(Workspace::repositories)
                .orElseThrow(() -> new IllegalArgumentException("Unknown workspace: " + workspaceId));
    }

    private static List<RepositoryRef> listRepositoriesInternal(java.sql.Connection connection,
                                                                String workspaceId)
            throws java.sql.SQLException {
        List<RepositoryRef> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM repositories WHERE workspace_id = ? ORDER BY added_at ASC")) {
            statement.setString(1, workspaceId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new RepositoryRef(
                            rs.getString("id"),
                            rs.getString("name"),
                            rs.getString("local_path"),
                            rs.getString("remote_url"),
                            rs.getString("branch"),
                            RepositoryAccessPolicy.valueOf(rs.getString("access_policy")),
                            Instant.parse(rs.getString("added_at"))));
                }
            }
        }
        return result;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
