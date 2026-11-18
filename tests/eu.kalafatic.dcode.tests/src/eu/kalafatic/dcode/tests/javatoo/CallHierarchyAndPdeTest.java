package eu.kalafatic.dcode.tests.javatoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dcode.java.tooling.tools.JavaCallHierarchyTool;
import eu.kalafatic.dcode.java.tooling.tools.PdeAnalyzeDependenciesTool;
import eu.kalafatic.dcode.java.tooling.tools.PdeInspectBundleTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5.6: call hierarchy and PDE bundle inspection / cross-bundle dependency resolution.
 */
class CallHierarchyAndPdeTest {

    private JavaToolingFixtures fixtures;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new JavaToolingFixtures();
    }

    @Test
    void callHierarchyFindsCallerOfInterfaceMethod() throws Exception {
        var tool = new JavaCallHierarchyTool(fixtures.workspaceManager);
        var result = tool.execute(fixtures.context,
                Map.of("path", "javarepo/core/src/main/java/com/example/core/GreetingImpl.java",
                        "method", "greet"));
        assertTrue(result.success(), result.output());
        JsonNode root = mapper.readTree(result.output());
        assertTrue(root.path("callerCount").asInt() >= 1,
                "expected at least one caller (App.main): " + result.output());
        boolean appCaller = false;
        for (JsonNode caller : root.path("callers")) {
            if (caller.path("resource").asText().contains("App.java")) {
                appCaller = true;
            }
        }
        assertTrue(appCaller, "expected a caller in App.java: " + result.output());
    }

    @Test
    void pdeInspectBundleReportsManifestAndPluginXml() throws Exception {
        var tool = new PdeInspectBundleTool(fixtures.workspaceManager);
        var result = tool.execute(fixtures.context,
                Map.of("path", "osgirepo/com.example.api"));
        assertTrue(result.success(), result.output());
        JsonNode root = mapper.readTree(result.output());
        assertEquals("com.example.api", root.path("symbolicName").asText());
        assertEquals("1.0.0.qualifier", root.path("version").asText());
        assertEquals("com.example.api",
                root.path("exportPackage").get(0).path("name").asText());
        assertEquals("providers",
                root.path("extensionPoints").get(0).path("id").asText());
    }

    @Test
    void pdeInspectBundleReportsExtensions() throws Exception {
        var tool = new PdeInspectBundleTool(fixtures.workspaceManager);
        var result = tool.execute(fixtures.context,
                Map.of("path", "osgirepo/com.example.impl"));
        assertTrue(result.success(), result.output());
        JsonNode root = mapper.readTree(result.output());
        assertEquals("com.example.impl", root.path("symbolicName").asText());
        assertEquals("com.example.api", root.path("requireBundle").get(0).asText());
        assertEquals("com.example.api.providers",
                root.path("extensions").get(0).path("point").asText());
    }

    @Test
    void osgiDependencyResolutionAcrossWorkspace() throws Exception {
        var tool = new PdeAnalyzeDependenciesTool(fixtures.workspaceManager);
        var result = tool.execute(fixtures.context,
                Map.of("repository", "osgirepo"));
        assertTrue(result.success(), result.output());
        JsonNode root = mapper.readTree(result.output());
        assertEquals(3, root.path("bundleCount").asInt());

        boolean implResolved = false;
        boolean brokenUnresolved = false;
        for (JsonNode bundle : root.path("bundles")) {
            if (bundle.path("symbolicName").asText().equals("com.example.impl")) {
                assertTrue(bundle.path("resolvedImports").has("com.example.api"),
                        "impl bundle import should resolve to the api bundle: " + result.output());
                assertEquals("com.example.api",
                        bundle.path("resolvedImports").path("com.example.api").asText());
                assertEquals(0, bundle.path("unresolvedImportCount").asInt());
                implResolved = true;
            }
            if (bundle.path("symbolicName").asText().equals("com.example.broken")) {
                assertEquals(1, bundle.path("unresolvedImportCount").asInt());
                assertEquals("com.nonexistent.pkg",
                        bundle.path("unresolvedImports").get(0).asText());
                brokenUnresolved = true;
            }
        }
        assertTrue(implResolved && brokenUnresolved);
    }
}
