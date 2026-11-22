package eu.kalafatic.dcode.harness.registry;

import java.util.List;

/**
 * Harness registry contract: the catalog of coding harnesses known to DCode, with
 * honest availability and integration status, plus the harness settings (default
 * selection and enablement) that the settings UI manages. Only harnesses that are
 * present, integrated and enabled can execute coding tasks.
 */
public interface HarnessRegistry {

    /** All known harness descriptors (integrated and external). */
    List<HarnessDescriptor> harnesses();

    /** One harness by id. */
    java.util.Optional<HarnessDescriptor> find(String id);

    /** Selects the default harness (persisted when the registry has a store). */
    void selectDefaultHarness(String harnessId);

    /** Enables or disables a harness for execution (persisted when a store exists). */
    void setHarnessEnabled(String harnessId, boolean enabled);

    /** The selected default harness id, if any. */
    java.util.Optional<String> defaultHarnessId();

    /** Whether the harness is enabled (harnesses are enabled by default). */
    boolean isEnabled(String harnessId);
}
