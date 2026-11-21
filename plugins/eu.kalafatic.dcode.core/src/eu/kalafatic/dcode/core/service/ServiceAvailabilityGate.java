package eu.kalafatic.dcode.core.service;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Retry-safe startup gate for manually wired OSGi bundles (family convention: no DS).
 *
 * <p>A bundle activator that depends on other services registers its startup work here:
 * the work runs immediately when all required services are already available, otherwise
 * as soon as the last missing service registers. Bundle start order becomes irrelevant.</p>
 */
public final class ServiceAvailabilityGate implements AutoCloseable {

    private final BundleContext context;
    private final Class<?>[] requiredServices;
    private final BooleanSupplier additionalReadiness;
    private final Runnable whenAvailable;
    private final AtomicBoolean fired = new AtomicBoolean();
    private final ServiceListener listener = this::onServiceEvent;

    public ServiceAvailabilityGate(BundleContext context, Class<?>[] requiredServices,
                                   Runnable whenAvailable) {
        this(context, requiredServices, () -> true, whenAvailable);
    }

    /**
     * Gate variant with an additional readiness condition. The startup work runs only
     * when all required services are available AND the readiness condition holds; every
     * subsequent service registration re-evaluates both.
     */
    public ServiceAvailabilityGate(BundleContext context, Class<?>[] requiredServices,
                                   BooleanSupplier additionalReadiness,
                                   Runnable whenAvailable) {
        this.context = Objects.requireNonNull(context, "context");
        this.requiredServices = Objects.requireNonNull(requiredServices, "requiredServices");
        this.additionalReadiness = Objects.requireNonNull(additionalReadiness,
                "additionalReadiness");
        this.whenAvailable = Objects.requireNonNull(whenAvailable, "whenAvailable");
        context.addServiceListener(listener);
    }

    /** Runs the startup work when all services are available. Returns true if it ran now. */
    public boolean runIfAvailable() {
        if (!allAvailable() || !additionalReadiness.getAsBoolean()) {
            return false;
        }
        if (!fired.compareAndSet(false, true)) {
            return false;
        }
        whenAvailable.run();
        return true;
    }

    private void onServiceEvent(ServiceEvent event) {
        if (fired.get()) {
            return;
        }
        if (event.getType() == ServiceEvent.REGISTERED) {
            runIfAvailable();
        }
    }

    private boolean allAvailable() {
        for (Class<?> service : requiredServices) {
            if (context.getServiceReference(service) == null) {
                return false;
            }
        }
        return true;
    }

    /** True when the startup work has already executed. */
    public boolean hasRun() {
        return fired.get();
    }

    @Override
    public void close() {
        context.removeServiceListener(listener);
    }
}
