package eu.kalafatic.dcode.java.tooling.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;
import eu.kalafatic.dcode.java.tooling.jdt.JavaWorkspaceManager;
import eu.kalafatic.dcode.java.tooling.jdt.ToolingException;

import java.util.List;
import java.util.Map;

/** Compilation diagnostics via the Eclipse batch compiler. */
@SuppressWarnings("restriction")
public final class JavaDiagnosticsTool extends AbstractJavaTool {

    public JavaDiagnosticsTool(JavaWorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("java_get_diagnostics",
                "Compile all sources of a Java module with the Eclipse compiler and report "
                        + "errors and warnings (real dependency checking).",
                "{\"type\":\"object\",\"properties\":{\"repository\":{\"type\":\"string\"},"
                        + "\"module\":{\"type\":\"string\",\"description\":\"module dir relative to the repository, optional\"}},"
                        + "\"required\":[\"repository\"]}",
                true, ToolScope.REPOSITORY);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        try {
            var handle = module(context, arguments);
            List<eu.kalafatic.dcode.java.tooling.jdt.ProblemRecord> problems =
                    workspaceManager.diagnostics(handle);
            ObjectNode root = object();
            root.put("repository", handle.repositoryName());
            root.put("module", handle.moduleDir());
            root.put("sourceCount", workspaceManager.sourceDirectories(handle).stream()
                    .mapToLong(dir -> {
                        try (var stream = java.nio.file.Files.walk(dir)) {
                            return stream.filter(p -> p.toString().endsWith(".java")).count();
                        } catch (Exception e) {
                            return 0;
                        }
                    }).sum());
            root.put("problemCount", problems.size());
            long errors = problems.stream().filter(p -> p.severity().equals("ERROR")).count();
            root.put("errorCount", errors);
            root.put("warningCount", problems.size() - errors);
            root.put("hasErrors", errors > 0);
            ArrayNode list = root.putArray("problems");
            for (var problem : problems) {
                ObjectNode node = list.addObject();
                node.put("severity", problem.severity());
                node.put("message", problem.message());
                node.put("file", problem.file());
                node.put("line", problem.line());
            }
            return ToolResult.ok(root.toString());
        } catch (ToolingException e) {
            return failure(e);
        }
    }
}
