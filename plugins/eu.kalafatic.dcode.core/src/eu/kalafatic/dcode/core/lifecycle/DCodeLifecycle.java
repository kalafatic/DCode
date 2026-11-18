package eu.kalafatic.dcode.core.lifecycle;

/**
 * Ordered startup/shutdown contract shared by all DCode runtime modes.
 *
 * <p>Stages must start in declaration order and stop in reverse order. A failed stage aborts
 * the remaining startup; shutdown still runs for already-started stages so resources are
 * never leaked.</p>
 */
public interface DCodeLifecycle {

    /** Starts stages up to and including the given stage, in order. Idempotent. */
    void startThrough(LifecycleStage stage) throws LifecycleException;

    /** Starts all stages. */
    void startAll() throws LifecycleException;

    /** Stops all started stages in reverse order. Idempotent. */
    void stop() throws LifecycleException;

    StageState getStageState(LifecycleStage stage);

    LifecycleStage getCurrentStage();

    boolean isStarted(LifecycleStage stage);

    boolean isReady();

    /** Registers a startup/shutdown action for a stage. */
    void registerAction(LifecycleStage stage, String name, LifecycleAction action);

    /** Registers a readiness probe evaluated against the given stage. */
    void addProbe(LifecycleStage stage, ReadinessProbe probe);

    interface LifecycleAction {
        void start() throws Exception;

        default void stop() throws Exception {
        }
    }
}
