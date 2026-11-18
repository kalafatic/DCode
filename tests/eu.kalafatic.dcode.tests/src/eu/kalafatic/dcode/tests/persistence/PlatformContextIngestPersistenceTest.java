package eu.kalafatic.dcode.tests.persistence;

import eu.kalafatic.dcode.core.event.InMemoryEventBus;
import eu.kalafatic.dcode.core.model.AgentRole;
import eu.kalafatic.dcode.core.model.MessageRole;
import eu.kalafatic.dcode.core.model.WorkspaceMode;
import eu.kalafatic.dcode.persistence.db.SchemaMigrationService;
import eu.kalafatic.dcode.persistence.db.SqliteDatabaseManager;
import eu.kalafatic.dcode.persistence.service.SqliteSessionService;
import eu.kalafatic.dcode.persistence.service.SqliteWorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for the platform-context integration: session metadata must
 * round-trip through SQLite persistence (previously dropped by mapSession),
 * and an ingested platform context must be stored as a SYSTEM message that
 * survives restart without overwriting user state.
 */
class PlatformContextIngestPersistenceTest {

    @TempDir
    Path temp;

    private SqliteDatabaseManager database;
    private InMemoryEventBus bus;
    private SqliteWorkspaceService workspaceService;
    private SqliteSessionService sessionService;

    @BeforeEach
    void setUp() {
        database = new SqliteDatabaseManager(temp.resolve("dcode-context.db"));
        new SchemaMigrationService(database).migrate();
        bus = new InMemoryEventBus(null, t -> {});
        workspaceService = new SqliteWorkspaceService(database, bus);
        sessionService = new SqliteSessionService(database, bus, workspaceService);
    }

    @Test
    void sessionMetadataRoundTripsThroughPersistence() {
        var workspace = workspaceService.createWorkspace("test-ws", WorkspaceMode.LOCAL,
                temp.resolve("repo").toString());
        var session = sessionService.createSession(new eu.kalafatic.dcode.core.service.SessionService
                .CreateSessionRequest("context test", workspace.id(), "provider", "model",
                AgentRole.MAIN, Map.of("contextId", "ctx-12345")));

        SqliteSessionService reopened = new SqliteSessionService(database, bus, workspaceService);
        var reloaded = reopened.getSession(session.getId()).orElseThrow();

        assertTrue(reloaded.getMetadata().containsKey("contextId"),
                "metadata_json must round-trip through persistence");
        assertEquals("ctx-12345", reloaded.getMetadata().get("contextId"));
    }

    @Test
    void ingestedPlatformContextIsStoredAsSystemMessage() {
        var workspace = workspaceService.createWorkspace("test-ws2", WorkspaceMode.LOCAL,
                temp.resolve("repo2").toString());
        var session = sessionService.createSession(new eu.kalafatic.dcode.core.service.SessionService
                .CreateSessionRequest("ingest test", workspace.id(), "provider", "model",
                AgentRole.MAIN, Map.of()));

        String envelope = "{\"source\":\"EVO_COGNITIVE_SERVICES\",\"contextId\":\"ctx-abc\","
                + "\"schemaVersion\":\"1.0\",\"tokenEstimate\":1200,"
                + "\"sections\":{\"projectContext\":{\"project\":\"/tmp/x\"}}}";
        sessionService.appendMessage(session.getId(), MessageRole.SYSTEM, envelope);

        List<eu.kalafatic.dcode.core.model.Message> messages = sessionService.listMessages(session.getId());
        assertEquals(1, messages.size());
        assertEquals(MessageRole.SYSTEM, messages.get(0).role());
        assertTrue(messages.get(0).content().contains("EVO_COGNITIVE_SERVICES"));
        assertTrue(messages.get(0).content().contains("ctx-abc"));

        // Simulated restart: the ingested context survives.
        SqliteSessionService reopened = new SqliteSessionService(database, bus, workspaceService);
        List<eu.kalafatic.dcode.core.model.Message> reloaded = reopened.listMessages(session.getId());
        assertEquals(1, reloaded.size());
        assertTrue(reloaded.get(0).content().contains("ctx-abc"));
    }
}
