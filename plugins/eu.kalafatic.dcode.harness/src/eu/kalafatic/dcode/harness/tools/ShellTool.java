package eu.kalafatic.dcode.harness.tools;

import eu.kalafatic.dcode.harness.tool.Tool;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controlled shell execution inside the workspace.
 *
 * <p>Potentially destructive: recorded in the audit log with the full command. The
 * working directory is confined to the workspace; environment is sanitized; execution is
 * time-bounded and cancellable.</p>
 */
public final class ShellTool implements Tool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final int MAX_OUTPUT_CHARS = 100_000;

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Process currentProcess;

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("shell",
                "Execute a shell command inside the workspace (bounded, cancellable). "
                        + "Potentially destructive; results are audited.",
                "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"},"
                        + "\"cwd\":{\"type\":\"string\",\"description\":\"relative working directory\"},"
                        + "\"timeoutSeconds\":{\"type\":\"integer\"}},\"required\":[\"command\"]}",
                false, ToolScope.WORKSPACE);
    }

    @Override
    public boolean supportsCancellation() {
        return true;
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        Process process = currentProcess;
        if (process != null) {
            process.destroy();
        }
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String command = ToolArgs.string(arguments, "command", null);
        if (command == null || command.isBlank()) {
            return ToolResult.failure("Argument 'command' is required");
        }
        int timeoutSeconds = Math.min(ToolArgs.integer(arguments, "timeoutSeconds",
                DEFAULT_TIMEOUT_SECONDS), 3600);
        String cwdArg = ToolArgs.string(arguments, "cwd", "");
        Path cwd;
        try {
            cwd = cwdArg.isBlank()
                    ? context.workspaceRoot()
                    : eu.kalafatic.dcode.harness.tool.ToolEngine.resolveContained(
                            context.workspaceRoot(), cwdArg);
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        }

        context.log("shell: " + command);
        cancelled.set(false);
        ProcessBuilder builder = new ProcessBuilder("/bin/bash", "-c", command);
        builder.directory(cwd.toFile());
        builder.environment().remove("OPENAI_API_KEY");
        builder.environment().remove("ANTHROPIC_API_KEY");
        builder.environment().remove("GITHUB_TOKEN");
        builder.environment().remove("GH_TOKEN");
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
                    // process died; output collected so far is enough
                }
            }, "dcode-shell-drain");
            drain.start();

            boolean finished = currentProcess.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                currentProcess.destroyForcibly();
                return ToolResult.failure("Command timed out after " + timeoutSeconds + "s: "
                        + command);
            }
            if (cancelled.get()) {
                return ToolResult.failure("Command cancelled: " + command);
            }
            drain.join(5000);
            int exitCode = currentProcess.exitValue();
            String out = String.join("\n", output);
            if (out.length() > MAX_OUTPUT_CHARS) {
                out = out.substring(0, MAX_OUTPUT_CHARS) + "\n... (truncated)";
            }
            if (exitCode != 0) {
                return ToolResult.failure("Command exited with code " + exitCode + ": "
                        + command + "\n" + out);
            }
            return ToolResult.ok(out.isEmpty() ? "(exit 0)" : out);
        } catch (IOException e) {
            return ToolResult.failure("Failed to execute command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (currentProcess != null) {
                currentProcess.destroyForcibly();
            }
            return ToolResult.failure("Shell execution interrupted");
        } finally {
            currentProcess = null;
        }
    }
}
