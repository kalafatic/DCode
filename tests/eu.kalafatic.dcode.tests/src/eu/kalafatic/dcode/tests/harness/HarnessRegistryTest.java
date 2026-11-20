package eu.kalafatic.dcode.tests.harness;

import eu.kalafatic.dcode.harness.registry.DefaultHarnessRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness registry contract: honest availability/integration reporting. The native
 * DCode harness is always usable; external CLI harnesses report availability without
 * pretending backend integration.
 */
class HarnessRegistryTest {

    @Test
    void dcodeHarnessIsIntegratedAndUsable() {
        var registry = new DefaultHarnessRegistry();
        var dcode = registry.find("dcode").orElseThrow();
        assertTrue(dcode.integrated());
        assertTrue(dcode.available());
        assertTrue(dcode.usable());
        assertTrue(dcode.capabilities().contains("tools"));
    }

    @Test
    void externalHarnessesAreHonestAboutIntegration() {
        var registry = new DefaultHarnessRegistry();
        assertEquals(4, registry.harnesses().size());
        for (var harness : registry.harnesses()) {
            if (harness.id().equals("dcode")) {
                continue;
            }
            // external harnesses are never falsely advertised as executable
            assertFalse(harness.integrated());
            assertFalse(harness.usable());
            assertTrue(harness.description().contains("not integrated"));
        }
    }

    @Test
    void availabilityDetectionIsHonest() {
        var registry = new DefaultHarnessRegistry();
        // opencode may or may not be installed on this machine; either way the
        // descriptor must report consistent availability and never claim usability
        var opencode = registry.find("opencode").orElseThrow();
        assertFalse(opencode.integrated());
        assertEquals(opencode.integrated() && opencode.available(), opencode.usable());
        // usability is the only execution gate
        assertFalse(opencode.usable());
    }
}
