package eu.kalafatic.dcode.tests.core;

import eu.kalafatic.dcode.core.lifecycle.DefaultDCodeLifecycle;
import eu.kalafatic.dcode.core.lifecycle.LifecycleException;
import eu.kalafatic.dcode.core.lifecycle.LifecycleStage;
import eu.kalafatic.dcode.core.lifecycle.ReadinessProbe;
import eu.kalafatic.dcode.core.lifecycle.StageState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleTest {

    @Test
    void stagesStartInOrderAndStopInReverse() throws Exception {
        DefaultDCodeLifecycle lifecycle = new DefaultDCodeLifecycle();
        List<String> log = new ArrayList<>();

        for (LifecycleStage stage : LifecycleStage.values()) {
            lifecycle.registerAction(stage, "action-" + stage, new DefaultDCodeLifecycle.LifecycleAction() {
                @Override
                public void start() {
                    log.add("start:" + stage);
                }

                @Override
                public void stop() {
                    log.add("stop:" + stage);
                }
            });
        }

        lifecycle.startAll();
        for (LifecycleStage stage : LifecycleStage.values()) {
            assertEquals(StageState.STARTED, lifecycle.getStageState(stage));
        }
        assertTrue(lifecycle.isReady());

        lifecycle.stop();
        assertEquals("start:BOOTSTRAP", log.get(0));
        assertEquals("start:CLIENTS", log.get(LifecycleStage.values().length - 1));
        assertEquals("stop:CLIENTS", log.get(LifecycleStage.values().length));
        assertEquals("stop:BOOTSTRAP", log.get(log.size() - 1));
    }

    @Test
    void idempotentStart() throws Exception {
        DefaultDCodeLifecycle lifecycle = new DefaultDCodeLifecycle();
        lifecycle.startThrough(LifecycleStage.CORE_SERVICES);
        lifecycle.startThrough(LifecycleStage.CORE_SERVICES);
        assertEquals(StageState.STARTED, lifecycle.getStageState(LifecycleStage.CORE_SERVICES));
        assertEquals(StageState.NOT_STARTED, lifecycle.getStageState(LifecycleStage.PERSISTENCE));
    }

    @Test
    void failedStageAbortsStartup() {
        DefaultDCodeLifecycle lifecycle = new DefaultDCodeLifecycle();
        lifecycle.registerAction(LifecycleStage.PERSISTENCE, "failing",
                new DefaultDCodeLifecycle.LifecycleAction() {
                    @Override
                    public void start() {
                        throw new IllegalStateException("disk missing");
                    }
                });

        assertThrows(LifecycleException.class, () -> lifecycle.startThrough(LifecycleStage.PERSISTENCE));
        assertEquals(StageState.FAILED, lifecycle.getStageState(LifecycleStage.PERSISTENCE));
        assertEquals(StageState.NOT_STARTED, lifecycle.getStageState(LifecycleStage.REPOSITORIES));
    }

    @Test
    void cannotRestartAfterStop() throws Exception {
        DefaultDCodeLifecycle lifecycle = new DefaultDCodeLifecycle();
        lifecycle.startThrough(LifecycleStage.CORE_SERVICES);
        lifecycle.stop();
        assertThrows(LifecycleException.class,
                () -> lifecycle.startThrough(LifecycleStage.CORE_SERVICES));
    }

    @Test
    void readinessReflectsProbes() throws Exception {
        DefaultDCodeLifecycle lifecycle = new DefaultDCodeLifecycle();
        lifecycle.startThrough(LifecycleStage.CORE_SERVICES);
        lifecycle.addProbe(LifecycleStage.CORE_SERVICES, new ReadinessProbe() {
            @Override
            public String name() {
                return "healthy-probe";
            }

            @Override
            public LifecycleStage stage() {
                return LifecycleStage.CORE_SERVICES;
            }

            @Override
            public String check() {
                return null;
            }
        });
        assertTrue(lifecycle.isReady());
    }

    @Test
    void failingProbeBreaksReadiness() throws Exception {
        DefaultDCodeLifecycle lifecycle = new DefaultDCodeLifecycle();
        lifecycle.startThrough(LifecycleStage.CORE_SERVICES);
        lifecycle.addProbe(LifecycleStage.CORE_SERVICES, new ReadinessProbe() {
            @Override
            public String name() {
                return "db-probe";
            }

            @Override
            public LifecycleStage stage() {
                return LifecycleStage.CORE_SERVICES;
            }

            @Override
            public String check() {
                return "database unreachable";
            }
        });
        assertFalse(lifecycle.isReady());
    }
}
