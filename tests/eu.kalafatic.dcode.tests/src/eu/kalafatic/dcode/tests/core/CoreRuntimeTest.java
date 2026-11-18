package eu.kalafatic.dcode.tests.core;

import eu.kalafatic.dcode.core.CoreRuntime;
import eu.kalafatic.dcode.core.lifecycle.LifecycleStage;
import eu.kalafatic.dcode.core.model.DCodeVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5.2 gate: the core runtime initializes independently of RCP/SWT.
 */
class CoreRuntimeTest {

    @Test
    void coreInitializesHeadless(@TempDir Path tempDir) throws Exception {
        System.setProperty("dcode.home", tempDir.toString());
        try {
            CoreRuntime runtime = new CoreRuntime();
            runtime.start();

            assertTrue(runtime.isStarted(LifecycleStage.CORE_SERVICES));
            assertTrue(runtime.isReady());
            assertEquals(DCodeVersion.APPLICATION_NAME, "DCode");

            assertTrue(tempDir.resolve("workspaces").toFile().exists());
            assertTrue(tempDir.resolve("artifacts").toFile().exists());
            assertTrue(tempDir.resolve("logs").toFile().exists());

            List<String> received = new ArrayList<>();
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            var sub = runtime.subscribe(e -> true, e -> {
                received.add(e.type().name());
                latch.countDown();
            });
            runtime.publish(eu.kalafatic.dcode.core.event.StandardEvent.system(
                    eu.kalafatic.dcode.core.event.DCodeEventType.SYSTEM_INFO, "{}"));
            assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, received.size());

            runtime.stop();
            assertFalse(runtime.isStarted(LifecycleStage.CORE_SERVICES));
        } finally {
            System.clearProperty("dcode.home");
        }
    }
}
