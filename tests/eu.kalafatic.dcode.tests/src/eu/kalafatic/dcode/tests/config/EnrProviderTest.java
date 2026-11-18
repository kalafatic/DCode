package eu.kalafatic.dcode.tests.config;

import eu.kalafatic.dcode.config.provider.ChatMessage;
import eu.kalafatic.dcode.config.provider.ChatRequest;
import eu.kalafatic.dcode.config.provider.ChatResponse;
import eu.kalafatic.dcode.config.provider.EnrProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrProviderTest {

    private MockHttpServer mock;
    private EnrProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        mock = MockHttpServer.start();
        provider = new EnrProvider("enr", "ENR Test", mock.baseUrl(), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    @Test
    void discoversModelsFromFamilyEndpoint() throws IOException {
        mock.on("GET", "/enr/v1/models", exchange -> mock.respond(exchange, 200,
                "[{\"id\":\"evo:latest\",\"name\":\"evo:latest\",\"provider\":\"ollama\","
                        + "\"engine\":\"ollama\",\"sizeBytes\":123,\"available\":true}]"));

        var models = provider.listModels();
        assertEquals(1, models.size());
        assertEquals("evo:latest", models.get(0).id());
        assertEquals("enr", models.get(0).providerId());
        assertTrue(models.get(0).available());
    }

    @Test
    void completesChatThroughFamilyContract() throws IOException {
        mock.on("POST", "/enr/v1/chat", exchange -> mock.respond(exchange, 200,
                "{\"content\":\"hello from enr\",\"finishReason\":\"stop\","
                        + "\"engine\":\"ollama\",\"model\":\"evo:latest\",\"durationMs\":12}"));

        ChatResponse response = provider.complete(new ChatRequest("evo:latest",
                List.of(ChatMessage.user("hi")), 0.5, 64));
        assertEquals("hello from enr", response.content());
        assertEquals("stop", response.finishReason());

        MockHttpServer.Request request = mock.requests().get(0);
        assertEquals("/enr/v1/chat", request.path());
        assertTrue(request.body().contains("\"model\":\"evo:latest\""));
        assertTrue(request.body().contains("\"temperature\":0.5"));
        assertTrue(request.body().contains("\"maxTokens\":64"));
        assertTrue(request.body().contains("\"role\":\"user\""));
    }

    @Test
    void connectionTestUsesHealthEndpoint() {
        mock.on("GET", "/health", exchange -> mock.respond(exchange, 200,
                "{\"application\":\"ENR\",\"version\":\"1.0\",\"status\":\"UP\","
                        + "\"ready\":true}"));
        assertTrue(provider.testConnection().success());
    }

    @Test
    void connectionTestReflectsFailure() {
        var result = provider.testConnection();
        assertFalse(result.success());
    }

    @Test
    void serverErrorMapsToProviderException() {
        mock.on("POST", "/enr/v1/chat", exchange -> mock.respond(exchange, 500,
                "{\"code\":\"INTERNAL_ERROR\",\"message\":\"engine failed\","
                        + "\"retryable\":false}"));
        var e = assertThrows(eu.kalafatic.dcode.config.provider.ProviderException.class,
                () -> provider.complete(new ChatRequest("m",
                        List.of(ChatMessage.user("x")), 0, 0)));
        assertTrue(e.getMessage().contains("500"));
    }

    @Test
    void configurationValidationRejectsShortToken() {
        var e = assertThrows(eu.kalafatic.dcode.config.provider.ProviderException.class,
                () -> provider.updateConfiguration(Map.of("authToken", "short")));
        assertTrue(e.getMessage().contains("too short"));
        assertEquals("", provider.getConfiguration().get("authToken"));
    }

    @Test
    void configuredTokenIsMaskedInConfiguration() {
        provider.updateConfiguration(Map.of("authToken", "secret-token-123"));
        assertTrue(provider.getConfiguration().get("authToken").contains("******"));
    }
}
