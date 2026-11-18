package eu.kalafatic.dcode.harness.tools;

import eu.kalafatic.dcode.harness.tool.Tool;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Reads a workspace file with bounded output. */
public final class ReadFileTool implements Tool {

    private static final int MAX_BYTES = 512 * 1024;

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("read_file",
                "Read a text file inside the workspace (bounded size).",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\","
                        + "\"description\":\"workspace-relative file path\"}},\"required\":[\"path\"]}",
                true, ToolScope.WORKSPACE);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String relative = ToolArgs.string(arguments, "path", null);
        if (relative == null) {
            return ToolResult.failure("Argument 'path' is required");
        }
        Path file = eu.kalafatic.dcode.harness.tool.ToolEngine.resolveContained(
                context.workspaceRoot(), relative);
        if (!Files.isRegularFile(file)) {
            return ToolResult.failure("No such file: " + relative);
        }
        try {
            long size = Files.size(file);
            if (size > MAX_BYTES) {
                return ToolResult.failure("File too large to read (" + size + " bytes, limit "
                        + MAX_BYTES + ")");
            }
            return ToolResult.ok(Files.readString(file));
        } catch (IOException e) {
            return ToolResult.failure("Failed to read file: " + e.getMessage());
        }
    }
}
