package eu.kalafatic.dcode.harness.registry;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Descriptor of one coding harness known to DCode.
 *
 * @param id stable harness id (e.g. {@code dcode}, {@code opencode})
 * @param name human-readable name
 * @param description one-line description
 * @param integrated whether this DCode backend can execute the harness
 * @param available whether the harness is present in this environment
 *        (external CLI harnesses are detected on PATH)
 * @param capabilities harness capabilities (e.g. {@code streaming}, {@code tools})
 */
public record HarnessDescriptor(
        String id,
        String name,
        String description,
        boolean integrated,
        boolean available,
        List<String> capabilities
) {
    public HarnessDescriptor {
        Objects.requireNonNull(id, "id");
        name = name == null ? id : name;
        description = description == null ? "" : description;
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }

    /** Usable for coding-task execution: integrated AND available. */
    public boolean usable() {
        return integrated && available;
    }
}
