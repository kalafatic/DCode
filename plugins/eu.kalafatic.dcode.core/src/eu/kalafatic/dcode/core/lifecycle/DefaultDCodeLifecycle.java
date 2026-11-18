package eu.kalafatic.dcode.core.lifecycle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Default lifecycle implementation.
 *
 * <p>Thread-safe. {@link #startThrough(LifecycleStage)} is idempotent; re-starting an
 * already-started stage is a no-op. Stopped runtimes cannot be restarted.</p>
 */
public final class DefaultDCodeLifecycle implements DCodeLifecycle {

    private record RegisteredAction(LifecycleStage stage, String name, LifecycleAction action) {
    }

    private final EnumMap<LifecycleStage, StageState> states = new EnumMap<>(LifecycleStage.class);
    private final List<RegisteredAction> actions = new ArrayList<>();
    private final Map<LifecycleStage, List<ReadinessProbe>> probes = new EnumMap<>(LifecycleStage.class);
    private final Set<RegisteredAction> startedActions =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private LifecycleStage currentStage = LifecycleStage.BOOTSTRAP;
    private boolean stopped;
    private volatile LifecycleException lastFailure;

    public DefaultDCodeLifecycle() {
        for (LifecycleStage stage : LifecycleStage.values()) {
            states.put(stage, StageState.NOT_STARTED);
            probes.put(stage, new ArrayList<>());
        }
    }

    @Override
    public synchronized void startThrough(LifecycleStage stage) throws LifecycleException {
        if (stopped) {
            throw new LifecycleException("Lifecycle already stopped; cannot restart");
        }
        for (LifecycleStage s : LifecycleStage.values()) {
            if (states.get(s) == StageState.STARTED) {
                currentStage = s;
                if (s == stage) {
                    return;
                }
                continue;
            }
            if (states.get(s) != StageState.NOT_STARTED) {
                throw new LifecycleException("Cannot start stage " + s + " while it is "
                        + states.get(s) + " (previous failure or in-progress start)");
            }
            states.put(s, StageState.STARTING);
            try {
                for (RegisteredAction action : actionsFor(s)) {
                    action.action().start();
                    startedActions.add(action);
                }
                states.put(s, StageState.STARTED);
            } catch (Exception e) {
                states.put(s, StageState.FAILED);
                lastFailure = new LifecycleException("Stage " + s + " failed to start", e);
                throw lastFailure;
            }
            currentStage = s;
            if (s == stage) {
                return;
            }
        }
    }

    @Override
    public synchronized void startAll() throws LifecycleException {
        startThrough(LifecycleStage.values()[LifecycleStage.values().length - 1]);
    }

    @Override
    public synchronized void stop() throws LifecycleException {
        if (stopped) {
            return;
        }
        stopped = true;
        LifecycleStage[] stages = LifecycleStage.values();
        for (int i = stages.length - 1; i >= 0; i--) {
            LifecycleStage s = stages[i];
            if (states.get(s) != StageState.STARTED) {
                continue;
            }
            states.put(s, StageState.STOPPING);
            Exception failure = null;
            for (int j = actionsFor(s).size() - 1; j >= 0; j--) {
                RegisteredAction action = actionsFor(s).get(j);
                if (!startedActions.contains(action)) {
                    continue;
                }
                try {
                    action.action().stop();
                } catch (Exception e) {
                    if (failure == null) {
                        failure = e;
                    }
                }
                startedActions.remove(action);
            }
            states.put(s, failure == null ? StageState.STOPPED : StageState.FAILED);
            if (failure != null) {
                throw new LifecycleException("Stage " + s + " failed to stop cleanly", failure);
            }
        }
    }

    @Override
    public synchronized StageState getStageState(LifecycleStage stage) {
        return states.get(stage);
    }

    @Override
    public synchronized LifecycleStage getCurrentStage() {
        return currentStage;
    }

    @Override
    public synchronized boolean isStarted(LifecycleStage stage) {
        return states.get(stage) == StageState.STARTED;
    }

    @Override
    public synchronized boolean isReady() {
        for (Map.Entry<LifecycleStage, List<ReadinessProbe>> e : probes.entrySet()) {
            LifecycleStage stage = e.getKey();
            if (states.get(stage) != StageState.STARTED) {
                continue;
            }
            for (ReadinessProbe probe : e.getValue()) {
                if (probe.check() != null) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public synchronized void registerAction(LifecycleStage stage, String name,
                                            LifecycleAction action) {
        actions.add(new RegisteredAction(stage, name, action));
    }

    @Override
    public synchronized void addProbe(LifecycleStage stage, ReadinessProbe probe) {
        probes.computeIfAbsent(stage, k -> new ArrayList<>()).add(probe);
    }

    private List<RegisteredAction> actionsFor(LifecycleStage stage) {
        List<RegisteredAction> result = new ArrayList<>();
        for (RegisteredAction a : actions) {
            if (a.stage() == stage) {
                result.add(a);
            }
        }
        return result;
    }

    /** Diagnostic dump of stage states (for logs and monitoring). */
    public synchronized Map<String, String> describe() {
        Map<String, String> result = new LinkedHashMap<>();
        for (LifecycleStage s : LifecycleStage.values()) {
            result.put(s.name(), states.get(s).name());
        }
        return result;
    }

    /** Last stage failure, or null. */
    public synchronized LifecycleException getLastFailure() {
        return lastFailure;
    }
}
