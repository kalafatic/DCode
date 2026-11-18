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

/** Replaces one text occurrence inside a workspace file (surgical edit). */
public final class EditFileTool implements Tool {

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("edit_file",
                "Replace an exact text block inside a workspace file. Fails when the old "
                        + "text occurs zero or more than once.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},"
                        + "\"oldText\":{\"type\":\"string\"},\"newText\":{\"type\":\"string\"}},"
                        + "\"required\":[\"path\",\"oldText\",\"newText\"]}",
                false, ToolScope.WORKSPACE);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String relative = ToolArgs.string(arguments, "path", null);
        String oldText = ToolArgs.string(arguments, "oldText", null);
        String newText = ToolArgs.string(arguments, "newText", null);
        if (relative == null || oldText == null || newText == null) {
            return ToolResult.failure("Arguments 'path', 'oldText' and 'newText' are required");
        }
        Path file = eu.kalafatic.dcode.harness.tool.ToolEngine.resolveContained(
                context.workspaceRoot(), relative);
        try {
            String content = Files.readString(file);
            int index = content.indexOf(oldText);
            if (index < 0) {
                return ToolResult.failure("oldText not found in " + relative);
            }
            if (content.indexOf(oldText, index + oldText.length()) >= 0) {
                return ToolResult.failure("oldText is ambiguous (occurs more than once) in "
                        + relative);
            }
            String updated = content.substring(0, index) + newText
                    + content.substring(index + oldText.length());
            Files.writeString(file, updated);
            context.log("edit_file: " + relative);
            return ToolResult.ok("Edited " + relative);
        } catch (IOException e) {
            return ToolResult.failure("Failed to edit file: " + e.getMessage());
        }
    }
}
