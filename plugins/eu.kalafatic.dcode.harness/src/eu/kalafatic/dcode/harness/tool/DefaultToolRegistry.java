package eu.kalafatic.dcode.harness.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory tool registry. */
public final class DefaultToolRegistry implements ToolRegistry {

    private final ConcurrentHashMap<String, Tool> tools = new ConcurrentHashMap<>();

    @Override
    public void register(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        Tool previous = tools.putIfAbsent(tool.definition().name(), tool);
        if (previous != null) {
            throw new IllegalArgumentException("Tool already registered: "
                    + tool.definition().name());
        }
    }

    @Override
    public void unregister(String name) {
        tools.remove(name);
    }

    @Override
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public List<Tool> tools() {
        return List.copyOf(tools.values());
    }

    @Override
    public List<ToolDefinition> definitions() {
        List<ToolDefinition> result = new ArrayList<>();
        for (Tool tool : tools.values()) {
            result.add(tool.definition());
        }
        return result;
    }
}
