package eu.kalafatic.dcode.tests.javatoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dcode.java.tooling.jdt.JavaProjectKind;
import eu.kalafatic.dcode.java.tooling.tools.JavaDiagnosticsTool;
import eu.kalafatic.dcode.java.tooling.tools.JavaProjectStructureTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5.6: Java project discovery and compilation diagnostics against real test projects.
 */
class JavaProjectDiscoveryAndDiagnosticsTest {

    private JavaToolingFixtures fixtures;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        fixtures = new JavaToolingFixtures();
    }

    @Test
    void discoversMavenEclipseAndOsgiProjects() throws Exception {
        var tool = new JavaProjectStructureTool(fixtures.workspaceManager);
        var result = tool.execute(fixtures.context,
                Map.of("repository", "javarepo"));
        assertTrue(result.success());
        JsonNode root = mapper.readTree(result.output());
        assertEquals(3, root.path("projectCount").asInt());
        assertTrue(root.path("projects").toString().contains("core"));
        assertTrue(root.path("projects").toString().contains("app"));
        assertTrue(root.path("projects").toString().contains("invalid"));

        var eclipse = tool.execute(fixtures.context,
                Map.of("repository", "eclipserepo"));
        assertTrue(eclipse.success());
        JsonNode eclipseRoot = mapper.readTree(eclipse.output());
        assertEquals(1, eclipseRoot.path("projectCount").asInt());

        var osgi = tool.execute(fixtures.context,
                Map.of("repository", "osgirepo"));
        assertTrue(osgi.success());
        JsonNode osgiRoot = mapper.readTree(osgi.output());
        assertEquals(3, osgiRoot.path("projectCount").asInt());
    }

    @Test
    void kindDetectionIsCorrect() {
        fixtures.workspaceManager.scanRepository(fixtures.workspace,
                fixtures.workspace.repositories().get(0));
        var handles = fixtures.workspaceManager.projectsOf("javarepo");
        assertEquals(3, handles.size());
        for (var handle : handles) {
            assertEquals(JavaProjectKind.MAVEN, handle.kind());
        }
    }

    @Test
    void healthyModuleCompilesWithoutErrors() throws Exception {
        var tool = new JavaDiagnosticsTool(fixtures.workspaceManager);
        var result = tool.execute(fixtures.context,
                Map.of("repository", "javarepo", "module", "core"));
        assertTrue(result.success());
        JsonNode root = mapper.readTree(result.output());
        assertEquals(0, root.path("errorCount").asInt());
        assertEquals("core", root.path("module").asText().endsWith("core") ? "core" : "");
    }

    @Test
    void missingDependencyIsReported() throws Exception {
        var tool = new JavaDiagnosticsTool(fixtures.workspaceManager);
        var result = tool.execute(fixtures.context,
                Map.of("repository", "javarepo", "module", "app"));
        assertTrue(result.success());
        JsonNode root = mapper.readTree(result.output());
        assertTrue(root.path("hasErrors").asBoolean(),
                "expected compile errors for the broken app module: " + result.output());
        boolean missingTypeReported = false;
        for (JsonNode problem : root.path("problems")) {
            if (problem.path("severity").asText().equals("ERROR")
                    && problem.path("message").asText().toLowerCase().contains("cannot be resolved")) {
                missingTypeReported = true;
            }
        }
        assertTrue(missingTypeReported, "expected 'cannot be resolved' diagnostics, got: "
                + result.output());
    }

    @Test
    void invalidJavaSourceIsReportedStructured() throws Exception {
        var tool = new JavaDiagnosticsTool(fixtures.workspaceManager);
        var result = tool.execute(fixtures.context,
                Map.of("repository", "javarepo", "module", "invalid"));
        assertTrue(result.success());
        JsonNode root = mapper.readTree(result.output());
        assertTrue(root.path("hasErrors").asBoolean(),
                "expected syntax errors for the invalid module: " + result.output());
        assertTrue(root.path("problems").size() > 0);
    }
}
