package eu.kalafatic.dcode.java.tooling.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;
import eu.kalafatic.dcode.java.tooling.jdt.AstHelper;
import eu.kalafatic.dcode.java.tooling.jdt.JavaWorkspaceManager;
import eu.kalafatic.dcode.java.tooling.jdt.ToolingException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Import analysis of one source file (resolution status, usage counts). */
@SuppressWarnings("restriction")
public final class JavaAnalyzeImportsTool extends AbstractJavaTool {

    public JavaAnalyzeImportsTool(JavaWorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("java_analyze_imports",
                "Analyze the imports of a Java source file: resolution status and usage "
                        + "counts (unused and unresolved imports are reported).",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},"
                        + "\"required\":[\"path\"]}",
                true, ToolScope.REPOSITORY);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        try {
            String pathArg = arg(arguments, "path");
            if (pathArg == null) {
                throw new ToolingException("Argument 'path' is required");
            }
            var handle = workspaceManager.projectForPath(context.workspace(), pathArg);
            Path file = eu.kalafatic.dcode.harness.tool.ToolEngine.resolveContained(
                    context.workspaceRoot(), pathArg);
            List<Path> sourceDirectories = workspaceManager.sourceDirectories(handle);
            var unit = AstHelper.parse(file, sourceDirectories,
                    workspaceManager.classpathString(handle));
            ObjectNode root = object();
            root.put("file", pathArg);
            ArrayNode imports = root.putArray("imports");
            int unresolved = 0;
            int unused = 0;
            for (AstHelper.ImportInfo info : AstHelper.analyzeImports(unit)) {
                ObjectNode node = imports.addObject();
                node.put("name", info.name());
                node.put("onDemand", info.onDemand());
                node.put("resolved", info.resolved());
                node.put("usages", info.usages());
                node.put("unused", info.resolved() && info.usages() == 0);
                if (!info.resolved()) {
                    unresolved++;
                }
                if (info.resolved() && info.usages() == 0) {
                    unused++;
                }
            }
            root.put("unresolvedCount", unresolved);
            root.put("unusedCount", unused);
            return ToolResult.ok(root.toString());
        } catch (ToolingException e) {
            return failure(e);
        }
    }
}
