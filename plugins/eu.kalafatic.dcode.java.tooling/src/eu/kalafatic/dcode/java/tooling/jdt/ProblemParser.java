package eu.kalafatic.dcode.java.tooling.jdt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the textual problem output of the Eclipse batch compiler
 * ({@code org.eclipse.jdt.core.compiler.batch.BatchCompiler}) into structured records.
 */
@SuppressWarnings("restriction")
public final class ProblemParser {

    // "1. ERROR in /abs/path/File.java (at line 12)"
    private static final Pattern HEADER = Pattern.compile(
            "^\\d+\\.\\s+(ERROR|WARNING)\\s+in\\s+(.+?)\\s+\\(at line\\s+(\\d+)\\)$");

    private ProblemParser() {
    }

    public static List<ProblemRecord> parse(String output) {
        List<ProblemRecord> problems = new ArrayList<>();
        if (output == null) {
            return problems;
        }
        String[] lines = output.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = HEADER.matcher(lines[i]);
            if (!matcher.matches()) {
                continue;
            }
            String severity = matcher.group(1);
            String file = matcher.group(2).trim();
            int line;
            try {
                line = Integer.parseInt(matcher.group(3));
            } catch (NumberFormatException e) {
                line = -1;
            }
            String message = "";
            for (int j = i + 1; j < lines.length; j++) {
                String lineText = lines[j];
                if (HEADER.matcher(lineText).matches()) {
                    break; // next problem block
                }
                String candidate = lineText.trim();
                if (candidate.startsWith("^")) {
                    // the message follows the caret marker
                    for (int k = j + 1; k < lines.length; k++) {
                        String next = lines[k].trim();
                        if (HEADER.matcher(lines[k]).matches() || next.startsWith("^")) {
                            break;
                        }
                        if (!next.isBlank()) {
                            message = next;
                            break;
                        }
                    }
                    break;
                }
                if (!candidate.isBlank() && !candidate.startsWith("^")) {
                    message = candidate;
                }
            }
            problems.add(new ProblemRecord(severity, message, file, line));
        }
        return problems;
    }
}
