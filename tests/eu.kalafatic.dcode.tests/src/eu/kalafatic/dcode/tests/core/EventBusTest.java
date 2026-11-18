package eu.kalafatic.dcode.tests.core;

import eu.kalafatic.dcode.core.event.DCodeEvent;
import eu.kalafatic.dcode.core.event.DCodeEventType;
import eu.kalafatic.dcode.core.event.InMemoryEventBus;
import eu.kalafatic.dcode.core.event.StandardEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventBusTest {

    @Test
    void synchronousDispatchRespectsFilters() {
        InMemoryEventBus bus = new InMemoryEventBus(null, t -> {});
        List<DCodeEvent> sessionEvents = new CopyOnWriteArrayList<>();
        List<DCodeEvent> toolEvents = new CopyOnWriteArrayList<>();

        bus.subscribe("s1", sessionEvents::add);
        bus.subscribe("s1", DCodeEventType.TOOL_CALL_STARTED, toolEvents::add);

        bus.publish(StandardEvent.of(DCodeEventType.SESSION_CREATED, "s1", "{}"));
        bus.publish(StandardEvent.of(DCodeEventType.TOOL_CALL_STARTED, "s1", "{\"tool\":\"read\"}"));
        bus.publish(StandardEvent.of(DCodeEventType.SESSION_CREATED, "s2", "{}"));

        assertEquals(2, sessionEvents.size());
        assertEquals(1, toolEvents.size());
        assertEquals(DCodeEventType.TOOL_CALL_STARTED, toolEvents.get(0).type());
    }

    @Test
    void unsubscribeStopsDelivery() {
        InMemoryEventBus bus = new InMemoryEventBus(null, t -> {});
        List<DCodeEvent> received = new CopyOnWriteArrayList<>();
        var subscription = bus.subscribe(e -> true, received::add);
        bus.publish(StandardEvent.system(DCodeEventType.SYSTEM_INFO, "{}"));
        subscription.unsubscribe();
        bus.publish(StandardEvent.system(DCodeEventType.SYSTEM_INFO, "{}"));
        assertEquals(1, received.size());
    }

    @Test
    void handlerFailureDoesNotBreakDispatch() {
        InMemoryEventBus bus = new InMemoryEventBus(null, t -> {});
        List<DCodeEvent> received = new CopyOnWriteArrayList<>();
        bus.subscribe(e -> true, e -> {
            throw new IllegalStateException("boom");
        });
        bus.subscribe(e -> true, received::add);
        bus.publish(StandardEvent.system(DCodeEventType.SYSTEM_INFO, "{}"));
        assertEquals(1, received.size());
    }

    @Test
    void asyncDispatchCompletes() throws InterruptedException {
        InMemoryEventBus bus = new InMemoryEventBus();
        CountDownLatch latch = new CountDownLatch(1);
        bus.subscribe(e -> true, e -> latch.countDown());
        bus.publish(StandardEvent.system(DCodeEventType.SYSTEM_INFO, "{}"));
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
