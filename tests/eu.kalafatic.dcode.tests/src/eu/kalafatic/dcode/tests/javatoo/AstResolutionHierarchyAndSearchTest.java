package eu.kalafatic.dcode.tests.javatoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dcode.java.tooling.tools.JavaAstSummaryTool;
import eu.kalafatic.dcode.java.tooling.tools.JavaFindImplementationsTool;
import eu.kalafatic.dcode.java.tooling.tools.JavaFindReferencesTool;
import eu.kalafatic.dcode.java.tooling.tools.JavaResolveTypeTool;
import eu.kalafatic.dcode.java.tooling.tools.JavaTypeHierarchyTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5.6: AST parsing with type resolution, type hierarchy, reference and implementation
 * search against real sources.
 */
class AstResolutionHierarchyAndSearchTest {

    private JavaToolingFixtures fixtures;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new JavaToolingFixtures();
    }

    @Test
    void astSummaryResolvesBindings() throws Exception {
        var tool = new JavaAstSummaryTool(fixtures.workspaceManager);
        var result = tool.execute(fixtures.context,
                Map.of("path", "javarepo/core/src/main/java/com/example/core/GreetingImpl.java"));
        assertTrue(result.success(), result.output());
        JsonNode root = mapper.readTree(result.output());
        JsonNode types = root.path("types");
        assertEquals(1, types.size());
        assertEquals("GreetingImpl", types.get(0).path("name").asText());
        assertEquals("com.example.core.GreetingImpl",
                types.get(0).path("qualifiedName").asText());
        assertEquals("class", types.get(0).path("kind").asText());
        assertEquals("java.lang.Object", types.get(0).path("superclass").asText());
        assertEquals("com.example.core.Greeting",
                types.get(0).path("interfaces").get(0).asText());

        boolean greetMethodFound = false;
        for (JsonNode method : root.path("methods")) {
            if (method.path("name").asText().equals("greet")
                    && method.path("returnType").asText().equals("java.lang.String")) {
                greetMethodFound = true;
            }
        }
        assertTrue(greetMethodFound, "expected resolved greet() method: " + result.output());

        boolean constructorCallFound = false;
        for (JsonNode field : root.path("fields")) {
            if (field.path("name").asText().equals("prefix")) {
                constructorCallFound = true;
            }
        }
        assertTrue(constructorCallFound);
    }

    @Test
    void resolveTypeReportsStructure() throws Exception {
        var tool = new JavaResolveTypeTool(fixtures.workspaceManager);
        var result = tool.execute(fixtures.context,
                Map.of("repository", "javarepo", "module", "core",
                        "type", "com.example.core.GreetingImpl"));
        assertTrue(result.success(), result.output());
        JsonNode root = mapper.readTree(result.output());
        assertEquals("com.example.core.GreetingImpl",
                root.path("qualifiedName").asText());
        assertEquals("class", root.path("kind").asText());
        assertEquals("java.lang.Object", root.path("superclass").asText());
        assertEquals("com.example.core.Greeting",
                root.path("interfaces").get(0).asText());
        assertTrue(root.path("methods").size() >= 2);
    }

    @Test
    void typeHierarchyFindsSubtypes() throws Exception {
        var tool = new JavaTypeHierarchyTool(fixtures.workspaceManager);
        var result = tool.execute(fixtures.context,
                Map.of("repository", "javarepo", "module", "core",
                        "type", "com.example.core.Greeting"));
        assertTrue(result.success(), result.output());
        JsonNode root = mapper.readTree(result.output());
        boolean implFound = false;
        for (JsonNode subtype : root.path("subtypes")) {
            if (subtype.asText().equals("com.example.core.GreetingImpl")) {
                implFound = true;
            }
        }
        assertTrue(implFound, "expected GreetingImpl as subtype: " + result.output());
    }

    @Test
    void referenceSearchFindsUsage() throws Exception {
        var tool = new JavaFindReferencesTool(fixtures.workspaceManager);
        var result = tool.execute(fixtures.context,
                Map.of("repository", "javarepo", "module", "core",
                        "kind", "type", "name", "com.example.core.Greeting"));
        assertTrue(result.success(), result.output());
        JsonNode root = mapper.readTree(result.output());
        assertTrue(root.path("referenceCount").asInt() >= 2,
                "expected references in GreetingImpl and App: " + result.output());
    }

    @Test
    void implementationSearchFindsImplementors() throws Exception {
        var tool = new JavaFindImplementationsTool(fixtures.workspaceManager);
        var result = tool.execute(fixtures.context,
                Map.of("repository", "javarepo", "module", "core",
                        "kind", "type", "name", "com.example.core.Greeting"));
        assertTrue(result.success(), result.output());
        JsonNode root = mapper.readTree(result.output());
        boolean implFound = false;
        for (JsonNode implementor : root.path("implementors")) {
            if (implementor.path("resource").asText().contains("GreetingImpl.java")) {
                implFound = true;
            }
        }
        assertTrue(implFound, "expected GreetingImpl implementor: " + result.output());
    }
}
