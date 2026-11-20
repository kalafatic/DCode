package eu.kalafatic.dcode.tests.config;

import eu.kalafatic.dcode.config.model.ModelDefinition;
import eu.kalafatic.dcode.config.provider.ChatMessage;
import eu.kalafatic.dcode.config.provider.ChatRequest;
import eu.kalafatic.dcode.config.provider.ChatResponse;
import eu.kalafatic.dcode.config.provider.ConnectionTestResult;
import eu.kalafatic.dcode.config.provider.LlmProvider;
import eu.kalafatic.dcode.config.routing.DefaultModelRouter;
import eu.kalafatic.dcode.config.runtime.DefaultRuntimeRegistry;
import eu.kalafatic.dcode.config.runtime.RuntimeAdapter;
import eu.kalafatic.dcode.config.runtime.RuntimeCapabilities;
import eu.kalafatic.dcode.config.runtime.RuntimeInfo;
import eu.kalafatic.dcode.config.runtime.RuntimeRegistration;
import eu.kalafatic.dcode.config.runtime.RuntimeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime registry contract: registrations with identity, multiple instances of one
 * type, enable/disable, default selection, model discovery and the ProviderRegistry
 * bridge consumed by the harness.
 */
class RuntimeRegistryTest {

    private DefaultRuntimeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultRuntimeRegistry();
    }

    @Test
    void registersRuntimesAndRejectsDuplicates() {
        registry.register(RuntimeRegistration.of("enr-dev", "ENR Dev", RuntimeType.ENR,
                "http://127.0.0.1:28780"), stubAdapter("enr-dev", RuntimeType.ENR));
        assertThrows(IllegalArgumentException.class, () ->
                registry.register(RuntimeRegistration.of("enr-dev", "ENR Dev 2",
                        RuntimeType.ENR, "http://127.0.0.1:28781"),
                        stubAdapter("enr-dev", RuntimeType.ENR)));
        assertEquals(1, registry.runtimes().size());
    }

    @Test
    void supportsMultipleInstancesOfSameType() {
        registry.register(RuntimeRegistration.of("ollama-local", "Local Ollama",
                RuntimeType.OLLAMA, "http://127.0.0.1:11434"),
                stubAdapter("ollama-local", RuntimeType.OLLAMA));
        registry.register(RuntimeRegistration.of("ollama-remote", "Remote Ollama",
                RuntimeType.OLLAMA, "http://10.0.0.5:11434"),
                stubAdapter("ollama-remote", RuntimeType.OLLAMA));
        List<RuntimeInfo> runtimes = registry.runtimes();
        assertEquals(2, runtimes.size());
        assertEquals(2, runtimes.stream().filter(r -> r.type() == RuntimeType.OLLAMA)
                .count());
    }

    @Test
    void enableDisableAndDefaultSelection() {
        registry.register(RuntimeRegistration.of("enr", "ENR", RuntimeType.ENR,
                "http://127.0.0.1:28780"), stubAdapter("enr", RuntimeType.ENR));
        registry.register(RuntimeRegistration.of("ollama", "Ollama", RuntimeType.OLLAMA,
                "http://127.0.0.1:11434"), stubAdapter("ollama", RuntimeType.OLLAMA));

        assertTrue(registry.isEnabled("enr"));
        registry.setEnabled("enr", false);
        assertFalse(registry.isEnabled("enr"));
        assertFalse(registry.info("enr").orElseThrow().enabled());

        registry.setDefault("enr", null);
        assertTrue(registry.info("enr").orElseThrow().defaultRuntime());
        assertEquals("enr", registry.defaultRuntime().orElseThrow().id());
        assertFalse(registry.info("ollama").orElseThrow().defaultRuntime());

        registry.setDefault("ollama", null);
        assertEquals("ollama", registry.defaultRuntime().orElseThrow().id());
    }

    @Test
    void unregisterRemovesOnlyRegistration() {
        registry.register(RuntimeRegistration.of("enr", "ENR", RuntimeType.ENR,
                "http://127.0.0.1:28780"), stubAdapter("enr", RuntimeType.ENR));
        registry.unregister("enr");
        assertFalse(registry.isRegistered("enr"));
        assertThrows(eu.kalafatic.dcode.config.provider.ProviderException.class,
                () -> registry.complete("enr", new ChatRequest("m",
                        List.of(ChatMessage.user("x")), 0, 0)));
    }

    @Test
    void modelDiscoverySkipsUnreachableRuntimes() {
        registry.register(RuntimeRegistration.of("enr", "ENR", RuntimeType.ENR,
                "http://127.0.0.1:28780"), stubAdapter("enr", RuntimeType.ENR,
                List.of(new ModelDefinition("m1", "m1", "enr", 0, true))));
        registry.register(RuntimeRegistration.of("broken", "Broken", RuntimeType.ENR,
                "http://127.0.0.1:9"), failingAdapter("broken", RuntimeType.ENR));
        List<ModelDefinition> models = registry.listAllModels();
        assertEquals(1, models.size());
        assertEquals("enr", models.get(0).providerId());
    }

    @Test
    void providerRegistryBridgeRoutesCompletions() {
        registry.register(RuntimeRegistration.of("enr", "ENR", RuntimeType.ENR,
                "http://127.0.0.1:28780"), stubAdapter("enr", RuntimeType.ENR));
        ChatResponse response = registry.complete("enr",
                new ChatRequest("m", List.of(ChatMessage.user("x")), 0, 0));
        assertEquals("stub", response.content());
        // ProviderRegistry view of the same registry
        assertEquals(1, registry.providers().size());
        assertTrue(registry.find("enr").isPresent());
        assertFalse(registry.find("nope").isPresent());
    }

    @Test
    void plainLlmProvidersAreWrappedWithoutOptionalCapabilities() {
        registry.register(new eu.kalafatic.dcode.tests.harness.FakeLlmProvider());
        RuntimeInfo info = registry.info("fake").orElseThrow();
        assertFalse(info.capabilities().modelLoad());
        assertFalse(info.capabilities().streaming());
        assertTrue(info.capabilities().modelDiscovery());
    }

    @Test
    void defaultRouterIsIndependentAndExplicit() {
        DefaultModelRouter router = new DefaultModelRouter();
        assertThrows(eu.kalafatic.dcode.config.provider.ProviderException.class,
                () -> router.resolve(eu.kalafatic.dcode.core.model.AgentRole.MAIN));
        router.setDefaultModel("enr", "qwen");
        assertEquals("enr", router.resolve(eu.kalafatic.dcode.core.model.AgentRole.MAIN)
                .providerId());
    }

    private static RuntimeAdapter stubAdapter(String id, RuntimeType type) {
        return stubAdapter(id, type, List.of());
    }

    private static RuntimeAdapter stubAdapter(String id, RuntimeType type,
                                              List<ModelDefinition> models) {
        return new RuntimeAdapter() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public RuntimeType type() {
                return type;
            }

            @Override
            public String endpoint() {
                return "http://127.0.0.1:1";
            }

            @Override
            public String displayName() {
                return id;
            }

            @Override
            public boolean isLocal() {
                return true;
            }

            @Override
            public RuntimeCapabilities capabilities() {
                return new RuntimeCapabilities(true, true, true, false, false, false,
                        true, true, Set.of());
            }

            @Override
            public List<ModelDefinition> listModels() {
                return models;
            }

            @Override
            public ConnectionTestResult testConnection() {
                return ConnectionTestResult.ok("stub reachable");
            }

            @Override
            public ChatResponse complete(ChatRequest request) {
                return new ChatResponse("stub", "stop", 0, 0);
            }
        };
    }

    private static RuntimeAdapter failingAdapter(String id, RuntimeType type) {
        return new RuntimeAdapter() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public RuntimeType type() {
                return type;
            }

            @Override
            public String endpoint() {
                return "http://127.0.0.1:9";
            }

            @Override
            public String displayName() {
                return id;
            }

            @Override
            public boolean isLocal() {
                return true;
            }

            @Override
            public List<ModelDefinition> listModels() {
                throw new eu.kalafatic.dcode.config.provider.ProviderException(
                        "unreachable");
            }

            @Override
            public ConnectionTestResult testConnection() {
                return ConnectionTestResult.error("unreachable");
            }

            @Override
            public ChatResponse complete(ChatRequest request) {
                throw new eu.kalafatic.dcode.config.provider.ProviderException(
                        "unreachable");
            }
        };
    }
}
