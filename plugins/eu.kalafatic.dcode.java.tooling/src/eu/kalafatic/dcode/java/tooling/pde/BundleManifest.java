package eu.kalafatic.dcode.java.tooling.pde;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for OSGi bundle manifests (MANIFEST.MF), preserving the standard header
 * folding rules. Structured access to the headers relevant for dependency analysis.
 */
@SuppressWarnings("restriction")
public final class BundleManifest {

    /** One OSGi package clause (name + attributes/directives). */
    public record PackageClause(String name, Map<String, String> attributes) {
    }

    private final Map<String, String> headers = new LinkedHashMap<>();
    private final Path path;

    public BundleManifest(Path manifestFile) {
        this.path = manifestFile;
        try {
            parse(Files.readString(manifestFile, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new eu.kalafatic.dcode.java.tooling.jdt.ToolingException(
                    "Cannot read MANIFEST.MF at " + manifestFile + ": " + e.getMessage(), e);
        }
    }

    private void parse(String content) {
        String[] lines = content.split("\r?\n");
        StringBuilder current = new StringBuilder();
        String currentName = null;
        for (String line : lines) {
            if (line.isEmpty()) {
                break; // main section ends
            }
            if (line.startsWith(" ")) {
                // continuation
                if (current != null) {
                    current.append(line.substring(1));
                }
            } else {
                flush(headers, currentName, current);
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    currentName = null;
                    current = new StringBuilder();
                    continue;
                }
                currentName = line.substring(0, colon).trim();
                current = new StringBuilder(line.substring(colon + 1).trim());
            }
        }
        flush(headers, currentName, current);
    }

    private static void flush(Map<String, String> headers, String name, StringBuilder value) {
        if (name != null && !name.isBlank() && value != null) {
            headers.put(name, value.toString().trim());
        }
    }

    public Path path() {
        return path;
    }

    public String header(String name) {
        return headers.get(name);
    }

    public boolean hasHeader(String name) {
        return headers.containsKey(name);
    }

    public String symbolicName() {
        String value = header("Bundle-SymbolicName");
        if (value == null) {
            return "";
        }
        int semicolon = value.indexOf(';');
        return (semicolon >= 0 ? value.substring(0, semicolon) : value).trim();
    }

    public String bundleVersion() {
        return header("Bundle-Version") != null ? header("Bundle-Version") : "";
    }

    public String bundleActivator() {
        return header("Bundle-Activator") != null ? header("Bundle-Activator") : "";
    }

    public List<String> requireBundle() {
        return splitClauses(header("Require-Bundle"));
    }

    public List<PackageClause> importPackage() {
        return packageClauses(header("Import-Package"));
    }

    public List<PackageClause> exportPackage() {
        return packageClauses(header("Export-Package"));
    }

    public List<String> requireCapability() {
        return splitClauses(header("Require-Capability"));
    }

    public List<String> bundleClassPath() {
        return splitClauses(header("Bundle-ClassPath"));
    }

    /** All headers (structured, for inspection tools). */
    public Map<String, String> allHeaders() {
        return Map.copyOf(headers);
    }

    /** Splits a comma-separated clause list into names (without attributes). */
    private static List<String> splitClauses(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String clause : value.split(",")) {
            String trimmed = clause.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            int semicolon = trimmed.indexOf(';');
            result.add((semicolon >= 0 ? trimmed.substring(0, semicolon) : trimmed).trim());
        }
        return result;
    }

    /** Splits a package clause list into names with attributes. */
    private static List<PackageClause> packageClauses(String value) {
        List<PackageClause> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String clause : value.split(",")) {
            String trimmed = clause.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            String[] parts = trimmed.split(";");
            String name = parts[0].trim();
            Map<String, String> attributes = new LinkedHashMap<>();
            for (int i = 1; i < parts.length; i++) {
                String part = parts[i].trim();
                int equals = part.indexOf('=');
                if (equals > 0) {
                    String key = part.substring(0, equals).trim();
                    String attributeValue = part.substring(equals + 1).trim();
                    if (attributeValue.startsWith("\"") && attributeValue.endsWith("\"")) {
                        attributeValue = attributeValue.substring(1,
                                attributeValue.length() - 1);
                    }
                    attributes.put(key, attributeValue);
                }
            }
            result.add(new PackageClause(name, Map.copyOf(attributes)));
        }
        return result;
    }
}
