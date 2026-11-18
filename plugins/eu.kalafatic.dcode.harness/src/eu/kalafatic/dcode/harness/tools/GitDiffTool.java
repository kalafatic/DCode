package eu.kalafatic.dcode.harness.tools;

import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.harness.tool.Tool;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;

import java.util.Map;

/** Unified diff of one repository's working-tree changes. */
public final class GitDiffTool implements Tool {

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("git_diff",
                "Unified diff of a repository's uncommitted changes.",
                "{\"type\":\"object\",\"properties\":{\"repository\":{\"type\":\"string\"}},"
                        + "\"required\":[\"repository\"]}",
                true, ToolScope.REPOSITORY);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String repoName = ToolArgs.string(arguments, "repository", null);
        RepositoryRef repository = repoName == null ? null
                : GitStatusTool.findRepository(context, repoName);
        if (repository == null) {
            return ToolResult.failure("Argument 'repository' must name a workspace repository");
        }
        String diff = context.repositories().diff(context.workspace(), repository);
        return ToolResult.ok(diff.isEmpty() ? "(no tracked changes)" : diff);
    }
}
