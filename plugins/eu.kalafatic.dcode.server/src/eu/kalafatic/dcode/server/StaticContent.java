package eu.kalafatic.dcode.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Static web-asset source for the DCode web UI. Served from the same origin as the API
 * so the frontend needs no hard-coded host or port (it derives its API base from
 * {@code window.location}).
 */
public interface StaticContent {

    /** Returns the content of a web asset (relative path), or null when absent. */
    InputStream open(String relativePath) throws IOException;

    /** Filesystem-backed static content (tests, standalone deployments). */
    final class FileSystem implements StaticContent {
        private final Path root;

        public FileSystem(Path root) {
            this.root = root;
        }

        @Override
        public InputStream open(String relativePath) throws IOException {
            Path resolved = root.resolve(relativePath).normalize();
            if (!resolved.startsWith(root.normalize())) {
                return null;
            }
            if (!Files.isRegularFile(resolved)) {
                return null;
            }
            return Files.newInputStream(resolved);
        }
    }

    /** OSGi bundle-entry-backed static content (product runtime). */
    final class BundleEntry implements StaticContent {
        private final org.osgi.framework.Bundle bundle;

        public BundleEntry(org.osgi.framework.Bundle bundle) {
            this.bundle = bundle;
        }

        @Override
        public InputStream open(String relativePath) throws IOException {
            if (relativePath.contains("..")) {
                return null;
            }
            java.net.URL url = bundle.getEntry("web/" + relativePath);
            if (url == null) {
                return null;
            }
            return url.openStream();
        }
    }
}
