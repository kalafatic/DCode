package eu.kalafatic.dcode.tests.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dcode.config.credentials.ConfigurationCredentialVault;
import eu.kalafatic.dcode.config.provider.DefaultProviderRegistry;
import eu.kalafatic.dcode.config.routing.DefaultModelRouter;
import eu.kalafatic.dcode.config.runtime.DefaultRuntimeRegistry;
import eu.kalafatic.dcode.config.runtime.JsonFileRuntimeStore;
import eu.kalafatic.dcode.config.runtime.RuntimeManager;
import eu.kalafatic.dcode.core.config.CoreConfiguration;
import eu.kalafatic.dcode.core.config.InMemoryConfigurationService;
import eu.kalafatic.dcode.core.event.InMemoryEventBus;
import eu.kalafatic.dcode.core.lifecycle.DefaultDCodeLifecycle;
import eu.kalafatic.dcode.core.port.PortAllocator;
import eu.kalafatic.dcode.harness.tool.DefaultToolRegistry;
import eu.kalafatic.dcode.server.DCodeApi;
import eu.kalafatic.dcode.server.DCodeHttpServer;
import eu.kalafatic.dcode.tests.config.MockHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unified model management API passthrough: DCode exposes the shared ENR
 * model management surface (/api/v1/models/...) without owning any model
 * management state. ENR is mocked here; the ENR implementation itself is
 * verified in its own repository.
 */
class ModelManagementApiTest {

    @TempDir
    Path temp;

    private MockHttpServer enr;
    private DCodeHttpServer server;
    private String baseUrl;
    private HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        enr = MockHttpServer.start();
        enr.on("GET", "/api/v1/models/providers", exchange -> enr.respond(exchange, 200,
                "{\"providers\":[{\"id\":\"ollama\",\"display_name\":\"Ollama\","
                        + "\"capabilities\":[\"MODEL_PULL\",\"MODEL_LIST\",\"MODEL_REMOVE\","
                        + "\"DOWNLOAD_PROGRESS\",\"MODEL_METADATA\"],\"reachable\":true}]}"));
        enr.on("GET", "/api/v1/models/providers/ollama/installed", exchange -> enr.respond(exchange, 200,
                "{\"provider\":\"ollama\",\"models\":[{\"provider\":\"ollama\","
                        + "\"id\":\"llama3:8b\",\"name\":\"llama3:8b\",\"digest\":\"sha256:x\","
                        + "\"size_bytes\":4661226496,\"modified_at\":1,\"installed\":true,"
                        + "\"details\":{}}]}"));
        enr.on("POST", "/api/v1/models/providers/ollama/pull", exchange -> enr.respond(exchange, 202,
                "{\"jobId\":\"job-1\",\"job_id\":\"job-1\",\"provider\":\"ollama\","
                        + "\"model\":\"qwen3-vl:30b\",\"status\":\"QUEUED\",\"created_at\":1}"));
        enr.on("GET", "/api/v1/models/downloads", exchange -> enr.respond(exchange, 200,
                "{\"downloads\":[{\"job_id\":\"job-1\",\"provider\":\"ollama\","
                        + "\"model\":\"qwen3-vl:30b\",\"status\":\"DOWNLOADING\",\"operation\":\"downloading\","
                        + "\"total_bytes\":1000,\"completed_bytes\":250,\"percent\":25,"
                        + "\"started_at\":1,\"updated_at\":2,\"completed_at\":0,\"error\":\"\","
                        + "\"layers\":[]}]}"));
        enr.on("GET", "/api/v1/models/downloads/job-1", exchange -> enr.respond(exchange, 200,
                "{\"job_id\":\"job-1\",\"provider\":\"ollama\",\"model\":\"qwen3-vl:30b\","
                        + "\"status\":\"DOWNLOADING\",\"operation\":\"downloading\","
                        + "\"total_bytes\":1000,\"completed_bytes\":500,\"percent\":50,"
                        + "\"started_at\":1,\"updated_at\":2,\"completed_at\":0,\"error\":\"\","
                        + "\"layers\":[]}"));
        enr.on("DELETE", "/api/v1/models/downloads/job-1", exchange -> enr.respond(exchange, 200,
                "{\"job_id\":\"job-1\",\"cancelled\":true}"));
        enr.on("DELETE", "/api/v1/models/providers/ollama/llama3:8b", exchange -> enr.respond(exchange, 200,
                "{\"provider\":\"ollama\",\"model\":\"llama3:8b\",\"removed\":true}"));
        enr.on("GET", "/api/v1/models/providers/nope/installed", exchange -> enr.respond(exchange, 404,
                "{\"error\":{\"code\":\"PROVIDER_NOT_FOUND\",\"message\":\"Unknown model provider: nope\"}}"));
        System.setProperty("dcode.enr.endpoint", enr.baseUrl());

        InMemoryEventBus bus = new InMemoryEventBus(null, t -> {});
        InMemoryConfigurationService configuration =
                new InMemoryConfigurationService(CoreConfiguration.defaults(temp));
        RuntimeManager runtimes = new RuntimeManager(new DefaultRuntimeRegistry(),
                new JsonFileRuntimeStore(temp),
                new ConfigurationCredentialVault(configuration),
                new DefaultModelRouter());
        DefaultDCodeLifecycle lifecycle = new DefaultDCodeLifecycle();
        lifecycle.startAll();
        var agentRegistry = new eu.kalafatic.dcode.config.agent.DefaultAgentRegistry(
                new eu.kalafatic.dcode.config.agent.JsonFileAgentStore(temp));
        DCodeApi api = new DCodeApi(() -> null, () -> null,
                () -> null, runtimes, runtimes, new DefaultToolRegistry(), null,
                lifecycle, configuration, bus,
                () -> null, () -> null, () -> null, () -> null,
                () -> agentRegistry);

        int port = PortAllocator.allocate(49300, 100);
        server = new DCodeHttpServer("127.0.0.1", port, api);
        server.start(5000, false);
        api.setHttpPortActual(port, "127.0.0.1");
        baseUrl = "http://127.0.0.1:" + port;
        client = HttpClient.newBuilder()
                .proxy(java.net.ProxySelector.of(null))
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("dcode.enr.endpoint");
        if (server != null) {
            server.stop();
        }
        if (enr != null) {
            enr.close();
        }
    }

    private JsonNode request(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json");
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        String text = response.body() == null || response.body().isBlank() ? "{}" : response.body();
        JsonNode node = mapper.readTree(text);
        return mapper.createObjectNode()
                .put("status", response.statusCode())
                .set("body", node);
    }

    @Test
    void providers_passthrough() throws Exception {
        JsonNode response = request("GET", "/api/v1/models/providers", null);
        assertEquals(200, response.path("status").asInt());
        JsonNode ollama = response.path("body").path("providers").get(0);
        assertEquals("ollama", ollama.path("id").asText());
        assertTrue(ollama.path("capabilities").toString().contains("MODEL_PULL"));
    }

    @Test
    void pull_returnsJobHandle() throws Exception {
        JsonNode response = request("POST", "/api/v1/models/ollama/pull",
                "{\"model\":\"qwen3-vl:30b\"}");
        assertEquals(200, response.path("status").asInt());
        assertEquals("job-1", response.path("body").path("jobId").asText());
        assertEquals("qwen3-vl:30b", response.path("body").path("model").asText());
    }

    @Test
    void pull_missingModel_rejected() throws Exception {
        JsonNode response = request("POST", "/api/v1/models/ollama/pull", "{}");
        assertEquals(400, response.path("status").asInt());
        assertEquals("VALIDATION_FAILED", response.path("body").path("code").asText());
    }

    @Test
    void jobStatus_passthroughWithProgress() throws Exception {
        JsonNode response = request("GET", "/api/v1/models/jobs/job-1", null);
        assertEquals(200, response.path("status").asInt());
        assertEquals("DOWNLOADING", response.path("body").path("status").asText());
        assertEquals(50, response.path("body").path("percent").asInt());
    }

    @Test
    void jobsList_passthrough() throws Exception {
        JsonNode response = request("GET", "/api/v1/models/jobs", null);
        assertEquals(200, response.path("status").asInt());
        assertEquals(1, response.path("body").path("downloads").size());
    }

    @Test
    void cancelJob_passthrough() throws Exception {
        JsonNode response = request("DELETE", "/api/v1/models/jobs/job-1", null);
        assertEquals(200, response.path("status").asInt());
        assertTrue(response.path("body").path("cancelled").asBoolean());
    }

    @Test
    void installed_passthrough() throws Exception {
        JsonNode response = request("GET", "/api/v1/models/ollama/installed", null);
        assertEquals(200, response.path("status").asInt());
        assertEquals("llama3:8b",
                response.path("body").path("models").get(0).path("id").asText());
    }

    @Test
    void remove_passthrough() throws Exception {
        JsonNode response = request("DELETE", "/api/v1/models/ollama/llama3:8b", null);
        assertEquals(200, response.path("status").asInt());
        assertTrue(response.path("body").path("removed").asBoolean());
    }

    @Test
    void upstreamProviderError_mappedToStructuredEnvelope() throws Exception {
        JsonNode response = request("GET", "/api/v1/models/nope/installed", null);
        assertEquals(503, response.path("status").asInt());
        assertEquals("UPSTREAM_UNAVAILABLE", response.path("body").path("code").asText());
        assertTrue(response.path("body").path("message").asText().contains("nope"));
    }
}
