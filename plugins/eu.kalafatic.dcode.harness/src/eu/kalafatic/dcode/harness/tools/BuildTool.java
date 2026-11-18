package eu.kalafatic.dcode.harness.tools;

import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.harness.tool.Tool;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Build verification of a repository using its detected build technology
 * (Maven wrapper, Maven, Gradle wrapper, Gradle). Executed inside the repository working
 * directory with a bounded timeout.
 */
public final class BuildTool implements Tool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 900;
    private static final int MAX_OUTPUT_CHARS = 100_000;

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Process currentProcess;

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("run_build",
                "Build a workspace repository with its detected build technology (Maven/"
                        + "Gradle wrapper preferred) and report success or failure with log output.",
                "{\"type\":\"object\",\"properties\":{\"repository\":{\"type\":\"string\"},"
                        + "\"goal\":{\"type\":\"string\",\"description\":\"build goal, default 'clean verify' for Maven, 'build' for Gradle\"}},"
                        + "\"required\":[\"repository\"]}",
                false, ToolScope.REPOSITORY);
    }

    @Override
    public boolean supportsCancellation() {
        return true;
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        if (currentProcess != null) {
            currentProcess.destroy();
        }
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
        String customGoal = ToolArgs.string(arguments, "goal", "");
        List<String> command = detectBuildCommand(repoDir, customGoal);
        if (command == null) {
            return ToolResult.failure("No supported build technology detected in " + repoName
                    + " (looked for mvnw, pom.xml, gradlew, build.gradle)");
        }

        context.log("run_build: " + String.join(" ", command) + " (" + repoName + ")");
        cancelled.set(false);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(repoDir.toFile());
        builder.redirectErrorStream(true);
        try {
            currentProcess = builder.start();
            List<String> output = new ArrayList<>();
            Thread drain = new Thread(() -> {
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                        currentProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.add(line);
                    }
                } catch (IOException ignored) {
                    // build died
                }
            }, "dcode-build-drain");
            drain.start();
            boolean finished = currentProcess.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                currentProcess.destroyForcibly();
                return ToolResult.failure("Build timed out after " + DEFAULT_TIMEOUT_SECONDS
                        + "s in " + repoName);
            }
            if (cancelled.get()) {
                return ToolResult.failure("Build cancelled in " + repoName);
            }
            drain.join(5000);
            int exitCode = currentProcess.exitValue();
            String out = String.join("\n", output);
            if (out.length() > MAX_OUTPUT_CHARS) {
                out = out.substring(out.length() - MAX_OUTPUT_CHARS);
            }
            if (exitCode != 0) {
                return ToolResult.failure("Build FAILED in " + repoName + " (exit " + exitCode
                        + ")\n" + out);
            }
            return ToolResult.ok("Build SUCCESS in " + repoName + "\n" + tail(out, 4000));
        } catch (IOException e) {
            return ToolResult.failure("Failed to start build: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (currentProcess != null) {
                currentProcess.destroyForcibly();
            }
            return ToolResult.failure("Build interrupted in " + repoName);
        } finally {
            currentProcess = null;
        }
    }

    private static List<String> detectBuildCommand(Path repoDir, String customGoal) {
        if (Files.isExecutable(repoDir.resolve("mvnw"))) {
            return List.of("./mvnw", "-B",
                    customGoal.isBlank() ? "clean" : customGoal, "-DskipTests=false");
        }
        if (Files.exists(repoDir.resolve("pom.xml"))) {
            return List.of("mvn", "-B",
                    customGoal.isBlank() ? "clean" : customGoal);
        }
        if (Files.isExecutable(repoDir.resolve("gradlew"))) {
            return List.of("./gradlew", customGoal.isBlank() ? "build" : customGoal);
        }
        if (Files.exists(repoDir.resolve("build.gradle"))
                || Files.exists(repoDir.resolve("build.gradle.kts"))) {
            return List.of("gradle", customGoal.isBlank() ? "build" : customGoal);
        }
        return null;
    }

    private static String tail(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return "...\n" + text.substring(text.length() - maxChars);
    }
}
