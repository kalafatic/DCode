package eu.kalafatic.dcode.tests.config;

import eu.kalafatic.dcode.config.credentials.ConfigurationCredentialVault;
import eu.kalafatic.dcode.config.credentials.CredentialVault;
import eu.kalafatic.dcode.config.git.GitAccountManager;
import eu.kalafatic.dcode.config.provider.DefaultProviderRegistry;
import eu.kalafatic.dcode.config.provider.OllamaProvider;
import eu.kalafatic.dcode.config.provider.ProviderException;
import eu.kalafatic.dcode.config.routing.DefaultModelRouter;
import eu.kalafatic.dcode.config.routing.ModelSelection;
import eu.kalafatic.dcode.core.config.CoreConfiguration;
import eu.kalafatic.dcode.core.config.InMemoryConfigurationService;
import eu.kalafatic.dcode.core.model.AgentRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderRegistryAndRoutingTest {

    @TempDir
    Path temp;

    private DefaultProviderRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        registry = new DefaultProviderRegistry();
        MockHttpServer mock = MockHttpServer.start();
        mock.on("GET", "/api/tags", exchange -> mock.respond(exchange, 200,
                "{\"models\":[{\"name\":\"coder\",\"size\":1}]}"));
        registry.register(new OllamaProvider("ollama", "Ollama", mock.baseUrl(),
                Duration.ofSeconds(5)));
        mock.close();
    }

    @Test
    void registryRejectsDuplicateProviders() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(OllamaProvider.localDefault()));
    }

    @Test
    void unknownProviderIsRejected() {
        assertThrows(ProviderException.class, () -> registry.complete("nope", null));
        assertThrows(ProviderException.class, () -> registry.listModels("nope"));
    }

    @Test
    void modelRouterResolvesRolesWithOverrides() {
        DefaultModelRouter router = new DefaultModelRouter();
        router.setDefaultModel("ollama", "small");
        assertEquals(new ModelSelection("ollama", "small"), router.resolve(AgentRole.MAIN));

        router.setRoleModel(AgentRole.PLANNER, "deepseek", "reasoner");
        assertEquals(new ModelSelection("deepseek", "reasoner"), router.resolve(AgentRole.PLANNER));
        assertEquals(new ModelSelection("ollama", "small"), router.resolve(AgentRole.REVIEWER));

        router.clearRoleModel(AgentRole.PLANNER);
        assertEquals(new ModelSelection("ollama", "small"), router.resolve(AgentRole.PLANNER));
    }

    @Test
    void routerFailsWithoutAnyModel() {
        DefaultModelRouter router = new DefaultModelRouter();
        assertThrows(ProviderException.class, () -> router.resolve(AgentRole.MAIN));
    }

    @Test
    void routerValidatesInputs() {
        DefaultModelRouter router = new DefaultModelRouter();
        assertThrows(IllegalArgumentException.class, () -> router.setDefaultModel("", "m"));
        assertThrows(IllegalArgumentException.class, () -> router.setDefaultModel("p", ""));
    }

    @Test
    void credentialVaultStoresAndMasks() {
        CredentialVault vault = new ConfigurationCredentialVault(
                new InMemoryConfigurationService(CoreConfiguration.defaults(temp)));
        assertFalse(vault.has("provider.deepseek.apiKey"));
        vault.put("provider.deepseek.apiKey", "sk-secret");
        assertTrue(vault.has("provider.deepseek.apiKey"));
        assertEquals("sk-secret", vault.get("provider.deepseek.apiKey").orElseThrow());
        assertEquals("***", vault.masked("provider.deepseek.apiKey"));
        assertTrue(vault.keys().contains("provider.deepseek.apiKey"));
        vault.remove("provider.deepseek.apiKey");
        assertFalse(vault.has("provider.deepseek.apiKey"));
    }

    @Test
    void gitAccountsKeepTokensOutOfListings() {
        CredentialVault vault = new ConfigurationCredentialVault(
                new InMemoryConfigurationService(CoreConfiguration.defaults(temp)));
        GitAccountManager manager = new GitAccountManager(vault);

        var account = manager.addAccount("work", "Petr", "petr@example.org");
        manager.setToken(account.id(), "ghp_supersecret");

        var listed = manager.listAccounts().get(0);
        assertTrue(!listed.toString().contains("ghp_supersecret"));
        assertEquals("work", listed.name());
        assertTrue(manager.hasToken(account.id()));
        assertEquals("ghp_supersecret", manager.token(account.id()).orElseThrow());

        var identity = manager.commitIdentity(account.id());
        assertEquals("Petr", identity.userName());
        assertEquals("petr@example.org", identity.userEmail());
    }
}
