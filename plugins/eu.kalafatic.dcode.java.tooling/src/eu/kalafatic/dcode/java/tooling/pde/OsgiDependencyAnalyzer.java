package eu.kalafatic.dcode.java.tooling.pde;

import eu.kalafatic.dcode.java.tooling.jdt.ToolingException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-bundle OSGi dependency analysis across a repository (and optionally across the
 * whole workspace): indexes every bundle's exported packages, then reports for each
 * bundle which imported packages are resolved by another bundle and which are
 * unresolved.
 */
@SuppressWarnings("restriction")
public final class OsgiDependencyAnalyzer {

    /** One analyzed bundle. */
    public record BundleAnalysis(
            String bundlePath,
            String symbolicName,
            List<String> unresolvedImports,
            Map<String, String> resolvedImports
    ) {
    }

    private final Map<String, String> packageProviders = new LinkedHashMap<>();

    /** Indexes the exported packages of all bundles under the given root directory. */
    public void indexBundles(List<Path> roots) {
        packageProviders.clear();
        for (Path root : roots) {
            collectBundles(root).forEach(this::indexBundle);
        }
    }

    /** Finds all bundle manifest locations under a root (bounded scan). */
    public static List<Path> collectBundles(Path root) {
        List<Path> manifests = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return manifests;
        }
        try (var stream = Files.walk(root, 8)) {
            for (Path file : stream.filter(p -> p.getFileName() != null
                    && p.getFileName().toString().equals("MANIFEST.MF")).toList()) {
                Path parent = file.getParent();
                if (parent != null && parent.getFileName() != null
                        && parent.getFileName().toString().equals("META-INF")) {
                    manifests.add(file);
                }
            }
        } catch (Exception e) {
            throw new ToolingException("Bundle scan failed under " + root, e);
        }
        return manifests;
    }

    private void indexBundle(Path manifestFile) {
        BundleManifest manifest = new BundleManifest(manifestFile);
        String symbolicName = manifest.symbolicName();
        if (symbolicName.isBlank()) {
            return;
        }
        for (BundleManifest.PackageClause export : manifest.exportPackage()) {
            packageProviders.putIfAbsent(export.name(), symbolicName);
        }
    }

    /** Analyzes one bundle against the indexed provider set. */
    public BundleAnalysis analyze(Path manifestFile) {
        BundleManifest manifest = new BundleManifest(manifestFile);
        List<String> unresolved = new ArrayList<>();
        Map<String, String> resolved = new LinkedHashMap<>();
        for (BundleManifest.PackageClause clause : manifest.importPackage()) {
            if (isJdkPackage(clause.name())) {
                resolved.put(clause.name(), "java");
                continue;
            }
            String provider = packageProviders.get(clause.name());
            if (provider != null && !provider.equals(manifest.symbolicName())) {
                resolved.put(clause.name(), provider);
            } else {
                unresolved.add(clause.name());
            }
        }
        return new BundleAnalysis(manifestFile.toString(), manifest.symbolicName(),
                unresolved, resolved);
    }

    /** Analyzes every bundle under a root. */
    public List<BundleAnalysis> analyzeAll(Path root) {
        List<BundleAnalysis> result = new ArrayList<>();
        for (Path manifestFile : collectBundles(root)) {
            result.add(analyze(manifestFile));
        }
        return result;
    }

    private static boolean isJdkPackage(String name) {
        return name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("org.w3c.") || name.startsWith("org.xml.")
                || name.startsWith("com.sun.");
    }
}
