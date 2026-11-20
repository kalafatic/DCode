package eu.kalafatic.dcode.tests.config;

import eu.kalafatic.dcode.config.agent.DefaultAgentRegistry;
import eu.kalafatic.dcode.config.agent.JsonFileAgentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent registry contract: built-in agents, custom agent persistence, default
 * selection and restart survival. Selection is dimension-independent — no agent
 * references a concrete provider.
 */
class AgentRegistryTest {

    @TempDir
    Path temp;

    @Test
    void builtInsArePresentAndImmutable() {
        DefaultAgentRegistry registry = new DefaultAgentRegistry(
                new JsonFileAgentStore(temp));
        assertEquals(3, registry.agents().size());
        var main = registry.find("main").orElseThrow();
        assertTrue(main.builtin());
        assertEquals("main", registry.defaultAgent().orElseThrow().id());
    }

    @Test
    void customAgentsPersistAndSurviveRestart() {
        var store = new JsonFileAgentStore(temp);
        DefaultAgentRegistry registry = new DefaultAgentRegistry(store);
        registry.register(new eu.kalafatic.dcode.config.agent.AgentDefinition(
                "kernel-hacker", "Kernel Hacker", "low-level", "MAIN",
                "You write minimal kernel code.", java.util.List.of(),
                java.util.List.of(), "ollama", "qwen3-coder-next:32k", false));
        registry.setDefaultAgent("kernel-hacker");

        DefaultAgentRegistry reloaded = new DefaultAgentRegistry(store);
        assertEquals(4, reloaded.agents().size());
        var custom = reloaded.find("kernel-hacker").orElseThrow();
        assertFalse(custom.builtin());
        assertEquals("ollama", custom.preferredRuntimeId());
        assertEquals("kernel-hacker", reloaded.defaultAgent().orElseThrow().id());
    }

    @Test
    void deletionRules() {
        DefaultAgentRegistry registry = new DefaultAgentRegistry(
                new JsonFileAgentStore(temp));
        assertThrows(IllegalArgumentException.class,
                () -> registry.unregister("main"));
        registry.register(new eu.kalafatic.dcode.config.agent.AgentDefinition(
                "tmp", "Temp", "", "MAIN", "", java.util.List.of(),
                java.util.List.of(), "", "", false));
        registry.unregister("tmp");
        assertTrue(registry.find("tmp").isEmpty());
    }
}
