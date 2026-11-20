package eu.kalafatic.dcode.config.agent;

import java.util.List;
import java.util.Optional;

/**
 * Agent registry contract: built-in and custom coding agents plus the default
 * selection. One registry per DCode runtime; sessions bind to an agent by id.
 */
public interface AgentRegistry {

    /** All agents (built-ins first). */
    List<AgentDefinition> agents();

    Optional<AgentDefinition> find(String id);

    /** Creates or replaces an agent. */
    AgentDefinition register(AgentDefinition agent);

    /** Removes a custom agent (built-ins are immutable). */
    void unregister(String id);

    Optional<AgentDefinition> defaultAgent();

    void setDefaultAgent(String id);
}
