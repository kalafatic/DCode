package eu.kalafatic.dcode.harness.tools;

import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.harness.tool.Tool;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;

import java.nio.file.Path;
import java.util.Map;

/**
 * Test execution of a repository. Java projects use Maven {@code test}; other build
 * technologies fall back to the build tool's test task when supported.
 */
public final class TestTool implements Tool {

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("run_tests",
                "Run the test suite of a workspace repository and report success or failure.",
                "{\"type\":\"object\",\"properties\":{\"repository\":{\"type\":\"string\"}},"
                        + "\"required\":[\"repository\"]}",
                false, ToolScope.REPOSITORY);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String repoName = ToolArgs.string(arguments, "repository", null);
        if (repoName == null) {
            return ToolResult.failure("Argument 'repository' is required");
        }
        RepositoryRef repository = GitStatusTool.findRepository(context, repoName);
        if (repository == null) {
            return ToolResult.failure("Unknown repository: " + repoName);
        }
        Path repoDir = context.repositories().resolveDir(context.workspace(), repository);
        String goal;
        if (java.nio.file.Files.isExecutable(repoDir.resolve("mvnw"))
                || java.nio.file.Files.exists(repoDir.resolve("pom.xml"))) {
            goal = "test";
        } else if (java.nio.file.Files.isExecutable(repoDir.resolve("gradlew"))
                || java.nio.file.Files.exists(repoDir.resolve("build.gradle"))) {
            goal = "test";
        } else {
            return ToolResult.failure("No supported test technology detected in " + repoName);
        }
        BuildTool buildTool = new BuildTool();
        return buildTool.execute(context, Map.of("repository", repoName, "goal", goal));
    }
}
