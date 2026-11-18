package eu.kalafatic.dcode.java.tooling.jdt;

/** One compiler problem produced by the batch compiler. */
public record ProblemRecord(
        String severity,
        String message,
        String file,
        int line
) {
}
