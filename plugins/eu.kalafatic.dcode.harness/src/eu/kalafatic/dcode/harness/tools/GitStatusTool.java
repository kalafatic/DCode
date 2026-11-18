package eu.kalafatic.dcode.harness.tools;

import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.harness.tool.Tool;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;

import java.util.Map;

/** Reports one repository's git state (branch, cleanliness, changed files). */
public final class GitStatusTool implements Tool {

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("git_status",
                "Report git state of a workspace repository: branch, cleanliness, changed files.",
                "{\"type\":\"object\",\"properties\":{\"repository\":{\"type\":\"string\","
                        + "\"description\":\"repository name in the workspace\"}},"
                        + "\"required\":[\"repository\"]}",
                true, ToolScope.REPOSITORY);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String repoName = ToolArgs.string(arguments, "repository", null);
        if (repoName == null) {
            return ToolResult.failure("Argument 'repository' is required");
        }
        RepositoryRef repository = findRepository(context, repoName);
        if (repository == null) {
            return ToolResult.failure("Unknown repository: " + repoName);
        }
        var info = context.repositories().info(context.workspace(), repository);
        var changed = context.repositories().changedFiles(context.workspace(), repository);
        StringBuilder out = new StringBuilder();
        out.append("repository: ").append(repository.name()).append('\n');
        out.append("branch: ").append(info.branch()).append('\n');
        out.append("clean: ").append(info.clean()).append('\n');
        out.append("commits: ").append(info.commitCount()).append('\n');
        out.append("access: ").append(repository.accessPolicy()).append('\n');
        out.append("changed files (").append(changed.size()).append("):\n");
        for (String file : changed) {
            out.append("  ").append(file).append('\n');
        }
        return ToolResult.ok(out.toString());
    }

    static RepositoryRef findRepository(ToolContext context, String name) {
        for (RepositoryRef repository : context.workspace().repositories()) {
            if (repository.name().equals(name)) {
                return repository;
            }
        }
        return null;
    }
}
