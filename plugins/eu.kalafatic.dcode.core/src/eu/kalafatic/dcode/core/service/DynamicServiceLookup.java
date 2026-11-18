package eu.kalafatic.dcode.core.service;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight dynamic service lookup over the standard OSGi API (no discouraged
 * {@code org.osgi.util.tracker} classes).
 *
 * <p>Caches the best-ranked service per type and invalidates the cache when any service
 * unregisters. Consumers resolve services at call time; bundle start order is
 * irrelevant.</p>
 */
public final class DynamicServiceLookup implements AutoCloseable {

    private final BundleContext context;
    private final ConcurrentHashMap<Class<?>, Object> cache = new ConcurrentHashMap<>();
    private final ServiceListener listener = this::onServiceEvent;

    public DynamicServiceLookup(BundleContext context) {
        this.context = Objects.requireNonNull(context, "context");
        context.addServiceListener(listener);
    }

    /** Returns the best-ranked service of the given type, or null. */
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) {
        Objects.requireNonNull(type, "type");
        Object cached = cache.get(type);
        if (cached != null) {
            return (T) cached;
        }
        ServiceReference<T> reference = context.getServiceReference(type);
        if (reference == null) {
            return null;
        }
        T service = context.getService(reference);
        if (service != null) {
            cache.put(type, service);
        }
        return service;
    }

    private void onServiceEvent(ServiceEvent event) {
        if (event.getType() == ServiceEvent.UNREGISTERING) {
            cache.clear();
        }
    }

    @Override
    public void close() {
        context.removeServiceListener(listener);
        cache.clear();
    }
}
