package eu.kalafatic.dcode.harness.tools;

import eu.kalafatic.dcode.harness.tool.Tool;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Creates or overwrites a workspace file. */
public final class WriteFileTool implements Tool {

    private static final int MAX_CONTENT_BYTES = 2 * 1024 * 1024;

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("write_file",
                "Create or overwrite a text file inside the workspace.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\","
                        + "\"description\":\"workspace-relative file path\"},"
                        + "\"content\":{\"type\":\"string\",\"description\":\"full file content\"}},"
                        + "\"required\":[\"path\",\"content\"]}",
                false, ToolScope.WORKSPACE);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String relative = ToolArgs.string(arguments, "path", null);
        String content = ToolArgs.string(arguments, "content", null);
        if (relative == null || content == null) {
            return ToolResult.failure("Arguments 'path' and 'content' are required");
        }
        if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            return ToolResult.failure("Content too large");
        }
        Path file = eu.kalafatic.dcode.harness.tool.ToolEngine.resolveContained(
                context.workspaceRoot(), relative);
        try {
            Files.createDirectories(file.getParent() != null ? file.getParent()
                    : context.workspaceRoot());
            Files.writeString(file, content);
            context.log("write_file: " + relative + " (" + content.length() + " chars)");
            return ToolResult.ok("Wrote " + relative);
        } catch (IOException e) {
            return ToolResult.failure("Failed to write file: " + e.getMessage());
        }
    }
}
