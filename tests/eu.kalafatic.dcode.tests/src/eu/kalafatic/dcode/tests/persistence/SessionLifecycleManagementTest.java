package eu.kalafatic.dcode.tests.persistence;

import eu.kalafatic.dcode.core.event.InMemoryEventBus;
import eu.kalafatic.dcode.core.model.AgentRole;
import eu.kalafatic.dcode.core.model.MessageRole;
import eu.kalafatic.dcode.core.model.WorkspaceMode;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.persistence.db.SchemaMigrationService;
import eu.kalafatic.dcode.persistence.db.SqliteDatabaseManager;
import eu.kalafatic.dcode.persistence.service.SqliteSessionService;
import eu.kalafatic.dcode.persistence.service.SqliteWorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session rename/delete contract of the SQLite session service (inference workspace
 * session navigation).
 */
class SessionLifecycleManagementTest {

    @TempDir
    Path temp;

    private SqliteDatabaseManager database;
    private InMemoryEventBus bus;
    private SqliteSessionService sessionService;

    @BeforeEach
    void setUp() {
        database = new SqliteDatabaseManager(temp.resolve("dcode.db"));
        new SchemaMigrationService(database).migrate();
        bus = new InMemoryEventBus(null, t -> {});
        var workspaceService = new SqliteWorkspaceService(database, bus);
        sessionService = new SqliteSessionService(database, bus, workspaceService);
    }

    private String newSession(String title) {
        var workspace = new SqliteWorkspaceService(database, bus)
                .createWorkspace("ws", WorkspaceMode.LOCAL, temp.resolve("ws").toString());
        var session = sessionService.createSession(new SessionService.CreateSessionRequest(
                title, workspace.id(), "ollama", "qwen", AgentRole.MAIN, null));
        sessionService.appendMessage(session.getId(), MessageRole.USER, "hello");
        return session.getId();
    }

    @Test
    void renameUpdatesTitleAndSurvivesReload() {
        String id = newSession("original");
        sessionService.renameSession(id, "renamed");
        assertEquals("renamed", sessionService.getSession(id).orElseThrow().getTitle());
        // reloaded over the same database sees the rename
        var reloaded = new SqliteSessionService(database, bus,
                new SqliteWorkspaceService(database, bus));
        assertEquals("renamed", reloaded.getSession(id).orElseThrow().getTitle());
    }

    @Test
    void renameRejectsBlankTitle() {
        String id = newSession("original");
        assertThrows(IllegalArgumentException.class,
                () -> sessionService.renameSession(id, "   "));
        assertEquals("original", sessionService.getSession(id).orElseThrow().getTitle());
    }

    @Test
    void deleteRemovesSessionAndMessages() {
        String id = newSession("doomed");
        assertTrue(sessionService.listMessages(id).size() >= 1);
        sessionService.deleteSession(id);
        assertTrue(sessionService.getSession(id).isEmpty());
        assertTrue(sessionService.listMessages(id).isEmpty());
        // idempotent
        sessionService.deleteSession(id);
    }
}
