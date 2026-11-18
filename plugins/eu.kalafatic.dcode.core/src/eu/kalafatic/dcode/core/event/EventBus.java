package eu.kalafatic.dcode.core.event;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * DCode event bus contract.
 *
 * <p>There is exactly one event bus per DCode runtime. All services publish through it;
 * persistence, SSE streaming and UI observe through it. No parallel event mechanism may be
 * introduced (family rule).</p>
 */
public interface EventBus {

    /** Publishes an event to all matching subscriptions. */
    void publish(DCodeEvent event);

    /**
     * Registers a subscription. The handler must be fast; long work must be delegated.
     *
     * @return a subscription handle that can be used to unsubscribe
     */
    Subscription subscribe(Predicate<DCodeEvent> filter, Consumer<DCodeEvent> handler);

    /** Convenience: subscribe to all events of one session. */
    default Subscription subscribe(String sessionId, Consumer<DCodeEvent> handler) {
        return subscribe(e -> sessionId.equals(e.sessionId()), handler);
    }

    /** Convenience: subscribe to one event type of one session. */
    default Subscription subscribe(String sessionId, DCodeEventType type,
                                   Consumer<DCodeEvent> handler) {
        return subscribe(e -> sessionId.equals(e.sessionId()) && e.type() == type, handler);
    }

    /** Handle for an active subscription. */
    interface Subscription {
        void unsubscribe();
    }
}
