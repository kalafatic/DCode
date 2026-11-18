package eu.kalafatic.dcode.core.lifecycle;

/** A health probe contributed by a subsystem. */
public interface ReadinessProbe {

    String name();

    LifecycleStage stage();

    /** Returns null when healthy, otherwise a diagnostic message. */
    String check();
}
