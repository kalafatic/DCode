package eu.kalafatic.dcode.core.model;

import java.util.Objects;

/** One unified-diff hunk of a file change. */
public record DiffHunk(
        int startLine,
        int lineCount,
        String content
) {
    public DiffHunk {
        Objects.requireNonNull(content, "content");
    }
}
