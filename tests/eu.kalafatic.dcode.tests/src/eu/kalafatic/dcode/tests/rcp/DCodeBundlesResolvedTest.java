package eu.kalafatic.dcode.tests.rcp;

import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** All DCode bundles must be resolved (wired) in the OSGi runtime. */
class DCodeBundlesResolvedTest {

    @Test
    void allDCodeBundlesResolved() {
        Set<String> resolved = new HashSet<>();
        for (Bundle bundle : FrameworkUtil.getBundle(DCodeBundlesResolvedTest.class)
                .getBundleContext().getBundles()) {
            String name = bundle.getSymbolicName();
            if (name != null && name.startsWith("eu.kalafatic.dcode")) {
                resolved.add(name);
                assertTrue(bundle.getState() == Bundle.RESOLVED
                                || bundle.getState() == Bundle.ACTIVE
                                || bundle.getState() == Bundle.STARTING,
                        name + " must be resolved, state=" + bundle.getState());
            }
        }
        for (String expected : new String[]{
                "eu.kalafatic.dcode.core", "eu.kalafatic.dcode.config",
                "eu.kalafatic.dcode.repositories", "eu.kalafatic.dcode.harness",
                "eu.kalafatic.dcode.java.tooling", "eu.kalafatic.dcode.persistence",
                "eu.kalafatic.dcode.server", "eu.kalafatic.dcode.client",
                "eu.kalafatic.dcode.ui"}) {
            assertTrue(resolved.contains(expected), "bundle missing: " + expected);
        }
    }
}
