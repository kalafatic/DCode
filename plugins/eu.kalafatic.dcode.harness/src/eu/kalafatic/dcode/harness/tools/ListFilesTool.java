package eu.kalafatic.dcode.harness.tools;

import eu.kalafatic.dcode.harness.tool.Tool;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Lists the workspace directory tree (bounded depth and entry count). Skips VCS metadata
 * and build output directories.
 */
public final class ListFilesTool implements Tool {

    private static final int MAX_ENTRIES = 500;

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("list_files",
                "List files and directories in the workspace (bounded).",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\","
                        + "\"description\":\"relative directory, default workspace root\"},"
                        + "\"depth\":{\"type\":\"integer\",\"description\":\"max depth, default 2\"}}}",
                true, ToolScope.WORKSPACE);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String relative = ToolArgs.string(arguments, "path", "");
        int depth = Math.min(ToolArgs.integer(arguments, "depth", 2), 6);
        Path root = relative.isBlank()
                ? context.workspaceRoot()
                : eu.kalafatic.dcode.harness.tool.ToolEngine.resolveContained(
                        context.workspaceRoot(), relative);
        if (!Files.isDirectory(root)) {
            return ToolResult.failure("Not a directory: " + relative);
        }
        StringBuilder out = new StringBuilder();
        List<String> entries = new ArrayList<>();
        try {
            collect(root, root, depth, entries);
            if (entries.isEmpty()) {
                return ToolResult.ok("(empty directory)");
            }
            entries.sort(Comparator.naturalOrder());
            for (String entry : entries) {
                out.append(entry).append('\n');
            }
            return ToolResult.ok(out.toString());
        } catch (IOException e) {
            return ToolResult.failure("Failed to list files: " + e.getMessage());
        }
    }

    private void collect(Path base, Path dir, int depth, List<String> entries)
            throws IOException {
        if (entries.size() >= MAX_ENTRIES) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path path : stream.sorted().toList()) {
                if (entries.size() >= MAX_ENTRIES) {
                    return;
                }
                String name = path.getFileName().toString();
                if (Files.isDirectory(path)) {
                    if (isSkippedDirectory(name)) {
                        continue;
                    }
                    String rel = base.relativize(path).toString();
                    entries.add(rel + "/");
                    if (depth > 1) {
                        collect(base, path, depth - 1, entries);
                    }
                } else {
                    entries.add(base.relativize(path).toString());
                }
            }
        }
    }

    private static boolean isSkippedDirectory(String name) {
        return name.equals(".git") || name.equals("target") || name.equals("build")
                || name.equals("node_modules") || name.equals(".mvn")
                || name.equals("bin") || name.equals("out");
    }
}
