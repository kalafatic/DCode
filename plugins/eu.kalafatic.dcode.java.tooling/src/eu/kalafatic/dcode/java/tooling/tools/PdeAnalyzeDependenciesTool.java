package eu.kalafatic.dcode.java.tooling.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;
import eu.kalafatic.dcode.java.tooling.jdt.JavaWorkspaceManager;
import eu.kalafatic.dcode.java.tooling.jdt.ToolingException;
import eu.kalafatic.dcode.java.tooling.pde.OsgiDependencyAnalyzer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Cross-bundle OSGi package resolution across all workspace repositories. */
@SuppressWarnings("restriction")
public final class PdeAnalyzeDependenciesTool extends AbstractJavaTool {

    public PdeAnalyzeDependenciesTool(JavaWorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("pde_analyze_dependencies",
                "Resolve OSGi package imports against all exported packages of the "
                        + "workspace bundles and report unresolved imports per bundle.",
                "{\"type\":\"object\",\"properties\":{\"repository\":{\"type\":\"string\","
                        + "\"description\":\"repository whose bundles are analyzed; all workspace repositories provide the package index\"}},"
                        + "\"required\":[\"repository\"]}",
                true, ToolScope.WORKSPACE);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        try {
            var repository = repository(context, arg(arguments, "repository"));
            OsgiDependencyAnalyzer analyzer = new OsgiDependencyAnalyzer();
            List<Path> roots = new ArrayList<>();
            for (var repo : context.workspace().repositories()) {
                roots.add(Path.of(repo.localPath()));
            }
            analyzer.indexBundles(roots);
            var analyses = analyzer.analyzeAll(Path.of(repository.localPath()));
            ObjectNode root = object();
            root.put("repository", repository.name());
            root.put("bundleCount", analyses.size());
            int unresolvedTotal = 0;
            ArrayNode bundles = root.putArray("bundles");
            for (OsgiDependencyAnalyzer.BundleAnalysis analysis : analyses) {
                ObjectNode node = bundles.addObject();
                node.put("symbolicName", analysis.symbolicName());
                node.put("bundlePath", analysis.bundlePath());
                node.put("unresolvedImportCount", analysis.unresolvedImports().size());
                ArrayNode unresolved = node.putArray("unresolvedImports");
                analysis.unresolvedImports().forEach(unresolved::add);
                ObjectNode resolved = node.putObject("resolvedImports");
                analysis.resolvedImports().forEach(resolved::put);
                unresolvedTotal += analysis.unresolvedImports().size();
            }
            root.put("unresolvedTotal", unresolvedTotal);
            return ToolResult.ok(root.toString());
        } catch (ToolingException e) {
            return failure(e);
        }
    }
}
