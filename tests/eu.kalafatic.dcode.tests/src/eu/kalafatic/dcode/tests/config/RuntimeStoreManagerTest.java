package eu.kalafatic.dcode.tests.config;

import eu.kalafatic.dcode.config.credentials.ConfigurationCredentialVault;
import eu.kalafatic.dcode.config.routing.DefaultModelRouter;
import eu.kalafatic.dcode.config.runtime.DefaultRuntimeRegistry;
import eu.kalafatic.dcode.config.runtime.JsonFileRuntimeStore;
import eu.kalafatic.dcode.config.runtime.RuntimeManager;
import eu.kalafatic.dcode.config.runtime.RuntimeRegistration;
import eu.kalafatic.dcode.config.runtime.RuntimeStoreData;
import eu.kalafatic.dcode.config.runtime.RuntimeType;
import eu.kalafatic.dcode.core.config.CoreConfiguration;
import eu.kalafatic.dcode.core.config.InMemoryConfigurationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime persistence: registrations survive a manager restart, credentials stay in
 * the vault (never in the JSON file), removal deletes the registration and the vault
 * entries only.
 */
class RuntimeStoreManagerTest {

    @TempDir
    Path temp;

    @Test
    void storeRoundTripsRegistrationsAndDefaults() {
        JsonFileRuntimeStore store = new JsonFileRuntimeStore(temp);
        RuntimeRegistration enr = new RuntimeRegistration("enr-prod", "ENR Prod",
                RuntimeType.ENR, "http://10.0.0.1:28780", true, true, java.util.Map.of());
        RuntimeRegistration ollama = new RuntimeRegistration("ollama-remote",
                "Remote Ollama", RuntimeType.OLLAMA, "http://10.0.0.2:11434",
                false, false, java.util.Map.of());
        store.save(new RuntimeStoreData("enr-prod", "qwen3-coder",
                List.of(enr, ollama)));

        RuntimeStoreData loaded = store.load();
        assertEquals("enr-prod", loaded.defaultRuntimeId());
        assertEquals("qwen3-coder", loaded.defaultModelId());
        assertEquals(2, loaded.registrations().size());
        assertEquals("ollama-remote", loaded.registrations().get(1).id());
        assertFalse(loaded.registrations().get(1).enabled());
    }

    @Test
    void managerSeedsDefaultsAndPersists() {
        InMemoryConfigurationService configuration =
                new InMemoryConfigurationService(CoreConfiguration.defaults(temp));
        RuntimeManager manager = RuntimeManager.create(configuration,
                new ConfigurationCredentialVault(configuration),
                new DefaultModelRouter());

        assertEquals(2, manager.runtimes().size());
        assertTrue(manager.isRegistered("enr"));
        assertTrue(manager.isRegistered("ollama"));
        assertEquals("enr", manager.defaultRuntime().orElseThrow().id());

        // a fresh manager on the same data dir restores the persisted registrations
        RuntimeManager reloaded = RuntimeManager.create(configuration,
                new ConfigurationCredentialVault(configuration),
                new DefaultModelRouter());
        assertEquals(2, reloaded.runtimes().size());
        assertTrue(reloaded.isRegistered("enr"));
    }

    @Test
    void secretsStayInVaultAndRemovalCleansUp() throws Exception {
        InMemoryConfigurationService configuration =
                new InMemoryConfigurationService(CoreConfiguration.defaults(temp));
        var vault = new ConfigurationCredentialVault(configuration);
        RuntimeManager manager = RuntimeManager.create(configuration, vault,
                new DefaultModelRouter());

        RuntimeRegistration deepseek = new RuntimeRegistration("deepseek", "DeepSeek",
                RuntimeType.OPENAI_COMPATIBLE, "https://api.deepseek.com/v1", true,
                false, java.util.Map.of());
        manager.register(deepseek, null, "sk-secret-key");

        // credential readable through the vault, never in the persisted file
        assertEquals("sk-secret-key",
                vault.get("runtime.deepseek.apiKey").orElseThrow());
        String persisted = manager.toString() + Files.readString(
                temp.resolve("runtimes.json"));
        assertFalse(persisted.contains("sk-secret-key"));
        assertEquals("***", manager.credentialState("deepseek").get("apiKey"));

        // removal deletes the registration and the vault entries only
        manager.unregister("deepseek");
        assertFalse(manager.isRegistered("deepseek"));
        assertTrue(vault.get("runtime.deepseek.apiKey").isEmpty());
        assertEquals(2, manager.runtimes().size());
    }

    @Test
    void defaultRuntimeAndModelPersist() {
        InMemoryConfigurationService configuration =
                new InMemoryConfigurationService(CoreConfiguration.defaults(temp));
        var vault = new ConfigurationCredentialVault(configuration);
        RuntimeManager manager = RuntimeManager.create(configuration, vault,
                new DefaultModelRouter());

        manager.setDefault("ollama", "qwen3-coder:32k");
        assertEquals("ollama", manager.defaultRuntime().orElseThrow().id());

        DefaultRuntimeRegistry fresh = new DefaultRuntimeRegistry();
        RuntimeManager reloaded = new RuntimeManager(fresh,
                new JsonFileRuntimeStore(temp), vault, new DefaultModelRouter());
        assertEquals("ollama", reloaded.defaultRuntime().orElseThrow().id());
    }

    @Test
    void endpointUpdateRecreatesAdapter() throws Exception {
        MockHttpServer mock = MockHttpServer.start();
        try {
            InMemoryConfigurationService configuration =
                    new InMemoryConfigurationService(CoreConfiguration.defaults(temp));
            RuntimeManager manager = RuntimeManager.create(configuration,
                    new ConfigurationCredentialVault(configuration),
                    new DefaultModelRouter());
            manager.register(RuntimeRegistration.of("enr-mock", "ENR Mock",
                    RuntimeType.ENR, "http://127.0.0.1:9"), null, null);
            assertFalse(manager.testConnection("enr-mock").success());

            mock.on("GET", "/enr/v1/health", exchange -> mock.respond(exchange, 200,
                    "{\"application\":\"ENR\",\"version\":\"1.0\",\"status\":\"UP\","
                            + "\"ready\":true}"));
            manager.update(RuntimeRegistration.of("enr-mock", "ENR Mock",
                    RuntimeType.ENR, mock.baseUrl()), null, null);
            assertEquals(mock.baseUrl(), manager.info("enr-mock").orElseThrow()
                    .endpoint());
            assertTrue(manager.testConnection("enr-mock").success());
        } finally {
            mock.close();
        }
    }
}
