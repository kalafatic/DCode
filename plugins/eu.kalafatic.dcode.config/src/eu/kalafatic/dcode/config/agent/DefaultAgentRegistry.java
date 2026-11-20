package eu.kalafatic.dcode.config.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default agent registry: shipped built-in agents plus persisted custom agents.
 * The default selection persists and survives restarts.
 */
public final class DefaultAgentRegistry implements AgentRegistry {

    /** Built-in agents (role-aligned with the harness {@code AgentRole}). */
    private static final List<AgentDefinition> BUILT_INS = List.of(
            new AgentDefinition("main", "General Coding Agent",
                    "All-purpose coding agent (default role MAIN)",
                    "MAIN",
                    "You are a precise coding agent. Plan, use tools, verify results.",
                    List.of(), List.of(), "", "", true),
            new AgentDefinition("senior-java", "Senior Java Developer",
                    "Senior Java engineer persona for JVM/OSGi/Eclipse projects",
                    "MAIN",
                    "You are a senior Java engineer. Prefer minimal, idiomatic, "
                            + "testable changes; verify with the java_* tools before "
                            + "modifying code.",
                    List.of(), List.of(), "", "", true),
            new AgentDefinition("reviewer", "Code Reviewer",
                    "Review-focused agent for changes and diffs",
                    "REVIEWER",
                    "You review changes critically: correctness, regressions, "
                            + "conventions, error handling. Report findings, propose "
                            + "fixes, do not modify code.",
                    List.of(), List.of(), "", "", true));

    private final JsonFileAgentStore store;
    private final Map<String, AgentDefinition> customAgents = new LinkedHashMap<>();
    private final AtomicReference<String> defaultAgentId = new AtomicReference<>();

    public DefaultAgentRegistry(JsonFileAgentStore store) {
        this.store = store;
        JsonFileAgentStore.AgentStoreData data = store.load();
        for (AgentDefinition agent : data.customAgents()) {
            customAgents.put(agent.id(), agent);
        }
        defaultAgentId.set(data.defaultAgentId() != null ? data.defaultAgentId()
                : BUILT_INS.get(0).id());
    }

    @Override
    public List<AgentDefinition> agents() {
        List<AgentDefinition> result = new ArrayList<>(BUILT_INS);
        result.addAll(customAgents.values());
        return List.copyOf(result);
    }

    @Override
    public Optional<AgentDefinition> find(String id) {
        return BUILT_INS.stream().filter(agent -> agent.id().equals(id)).findFirst()
                .or(() -> Optional.ofNullable(customAgents.get(id)));
    }

    @Override
    public AgentDefinition register(AgentDefinition agent) {
        customAgents.put(agent.id(), agent);
        persist();
        return agent;
    }

    @Override
    public void unregister(String id) {
        AgentDefinition existing = customAgents.get(id);
        if (existing == null) {
            throw new IllegalArgumentException("Unknown custom agent: " + id);
        }
        customAgents.remove(id);
        if (id.equals(defaultAgentId.get())) {
            defaultAgentId.set(BUILT_INS.get(0).id());
        }
        persist();
    }

    @Override
    public Optional<AgentDefinition> defaultAgent() {
        return find(defaultAgentId.get());
    }

    @Override
    public void setDefaultAgent(String id) {
        if (find(id).isEmpty()) {
            throw new IllegalArgumentException("Unknown agent: " + id);
        }
        defaultAgentId.set(id);
        persist();
    }

    private void persist() {
        store.save(new JsonFileAgentStore.AgentStoreData(defaultAgentId.get(),
                List.copyOf(customAgents.values())));
    }
}
