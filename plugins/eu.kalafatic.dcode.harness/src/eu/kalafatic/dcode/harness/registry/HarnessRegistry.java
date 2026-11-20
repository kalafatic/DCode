package eu.kalafatic.dcode.harness.registry;

import java.util.List;

/**
 * Harness registry contract: the catalog of coding harnesses known to DCode, with
 * honest availability and integration status. Only harnesses that are both present
 * and integrated into the backend can execute coding tasks.
 */
public interface HarnessRegistry {

    /** All known harness descriptors (integrated and external). */
    List<HarnessDescriptor> harnesses();

    /** One harness by id. */
    java.util.Optional<HarnessDescriptor> find(String id);
}
