package eu.kalafatic.dcode.harness.tool;

import java.util.List;
import java.util.Optional;

/** Tool registry contract: registration and discovery. */
public interface ToolRegistry {

    void register(Tool tool);

    void unregister(String name);

    Optional<Tool> find(String name);

    List<Tool> tools();

    List<ToolDefinition> definitions();
}
