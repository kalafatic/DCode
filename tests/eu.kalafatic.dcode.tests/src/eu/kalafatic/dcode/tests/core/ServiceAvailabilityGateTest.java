package eu.kalafatic.dcode.tests.core;

import eu.kalafatic.dcode.core.service.ServiceAvailabilityGate;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the readiness-supplier variant of the startup gate
 * (used by the Java tooling bundle to defer JDT wiring until the Eclipse
 * resources workspace exists).
 */
class ServiceAvailabilityGateTest {

    @Test
    void waitsForAdditionalReadinessBeforeFiring() {
        BundleContext context = FrameworkUtil.getBundle(ServiceAvailabilityGateTest.class)
                .getBundleContext();
        ServiceRegistration<Runnable> service = context.registerService(Runnable.class,
                () -> {}, null);
        try {
            AtomicBoolean ready = new AtomicBoolean(false);
            AtomicInteger fired = new AtomicInteger();
            try (ServiceAvailabilityGate gate = new ServiceAvailabilityGate(context,
                    new Class<?>[]{Runnable.class}, ready::get, fired::incrementAndGet)) {
                assertFalse(gate.runIfAvailable(), "readiness false: must not fire");
                ready.set(true);
                assertTrue(gate.runIfAvailable(), "readiness true: must fire now");
                assertTrue(gate.hasRun());
                assertFalse(gate.runIfAvailable(), "must fire exactly once");
                assertTrue(fired.get() == 1, "callback must run exactly once");
            }
        } finally {
            service.unregister();
        }
    }
}
