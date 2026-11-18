package eu.kalafatic.dcode.tests.javatoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.harness.tool.DefaultToolRegistry;
import eu.kalafatic.dcode.harness.tool.ToolEngine;
import eu.kalafatic.dcode.java.tooling.tools.JavaAstSummaryTool;
import eu.kalafatic.dcode.java.tooling.tools.JavaDiagnosticsTool;
import eu.kalafatic.dcode.java.tooling.tools.JavaRenameTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5.6: READ_ONLY enforcement, rename refactoring, and headless execution through the
 * real tool engine (no SWT anywhere on the path).
 */
class RenamePolicyAndHeadlessTest {

    private JavaToolingFixtures fixtures;
    private ObjectMapper mapper = new ObjectMapper();
    private ToolEngine toolEngine;

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new JavaToolingFixtures();
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(new JavaDiagnosticsTool(fixtures.workspaceManager));
        registry.register(new JavaAstSummaryTool(fixtures.workspaceManager));
        registry.register(new JavaRenameTool(fixtures.workspaceManager));
        toolEngine = new ToolEngine(registry,
                new eu.kalafatic.dcode.core.event.InMemoryEventBus(null, t -> {}));
    }

    @Test
    void renameRefactoringUpdatesReferencesAndVerifies() throws Exception {
        Path file = fixtures.root.resolve(
                "javarepo/core/src/main/java/com/example/core/GreetingImpl.java");
        var call = toolEngine.execute(fixtures.context, "java_rename",
                Map.of("path", "javarepo/core/src/main/java/com/example/core/GreetingImpl.java",
                        "kind", "type", "name", "GreetingImpl", "newName", "FriendlyGreeting"));
        assertTrue(call.errorMessage() == null, call.errorMessage());
        JsonNode root = mapper.readTree(call.resultJson() != null ? extractOutput(call) : "{}");
        assertTrue(root.path("changePerformed").asBoolean(),
                "rename result: " + extractOutput(call));
        assertEquals(0, root.path("errorCount").asInt());

        // the renamed file replaced the old one; references updated
        Path renamed = fixtures.root.resolve(
                "javarepo/core/src/main/java/com/example/core/FriendlyGreeting.java");
        assertFalse(Files.exists(file), "old file must be renamed away");
        assertTrue(Files.exists(renamed), "renamed file must exist");
        String content = Files.readString(renamed);
        assertTrue(content.contains("class FriendlyGreeting"), content);
        // reference updates: the app module callsite now uses the new name
        String appSource = Files.readString(fixtures.root.resolve(
                "javarepo/app/src/main/java/com/example/app/App.java"));
        assertTrue(appSource.contains("FriendlyGreeting"),
                "reference in App.java must be updated: " + appSource);
    }

    @Test
    void readOnlyRepositoryRejectsRename() throws Exception {
        var readOnlyContext = fixtures.readOnlyContext("javarepo");
        var call = toolEngine.execute(readOnlyContext, "java_rename",
                Map.of("path", "javarepo/core/src/main/java/com/example/core/GreetingImpl.java",
                        "kind", "type", "name", "GreetingImpl", "newName", "Nope"));
        String output = extractOutput(call);
        assertTrue(output.contains("READ_ONLY"), "expected READ_ONLY rejection, got: " + output);
        // file untouched
        assertTrue(Files.exists(fixtures.root.resolve(
                "javarepo/core/src/main/java/com/example/core/GreetingImpl.java")));
    }

    @Test
    void headlessExecutionThroughToolEngine() throws Exception {
        // The surefire runtime has no display dependency for JDT Core; running the full
        // tool set through the ToolEngine proves headless operation.
        var structureCall = toolEngine.execute(fixtures.context, "java_get_diagnostics",
                Map.of("repository", "javarepo", "module", "core"));
        assertTrue(structureCall.errorMessage() == null, structureCall.errorMessage());
        JsonNode root = mapper.readTree(extractOutput(structureCall));
        assertEquals(0, root.path("errorCount").asInt());

        var astCall = toolEngine.execute(fixtures.context, "java_ast_summary",
                Map.of("path", "javarepo/core/src/main/java/com/example/core/Greeting.java"));
        assertTrue(astCall.errorMessage() == null, astCall.errorMessage());
        assertTrue(extractOutput(astCall).contains("com.example.core.Greeting"));
    }

    private static String extractOutput(eu.kalafatic.dcode.core.model.ToolCall call) {
        try {
            return new ObjectMapper().readTree(call.resultJson()).path("output").asText("");
        } catch (Exception e) {
            return call.resultJson() != null ? call.resultJson() : "";
        }
    }
}
