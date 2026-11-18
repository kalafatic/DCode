package eu.kalafatic.dcode.harness.tool;

/** Result of one tool execution. */
public record ToolResult(
        boolean success,
        String output,
        String error
) {
    public static ToolResult ok(String output) {
        return new ToolResult(true, output == null ? "" : output, "");
    }

    public static ToolResult failure(String error) {
        return new ToolResult(false, "", error == null ? "Tool execution failed" : error);
    }
}
