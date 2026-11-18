package eu.kalafatic.dcode.tests.config;

import eu.kalafatic.dcode.config.provider.ChatRequest;
import eu.kalafatic.dcode.config.provider.ChatMessage;
import eu.kalafatic.dcode.config.provider.ChatResponse;
import eu.kalafatic.dcode.config.provider.OllamaProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaProviderTest {

    private MockHttpServer mock;
    private OllamaProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        mock = MockHttpServer.start();
        provider = new OllamaProvider("ollama", "Ollama Test", mock.baseUrl(),
                Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    @Test
    void discoversModelsFromTags() throws IOException {
        mock.on("GET", "/api/tags", exchange -> mock.respond(exchange, 200,
                "{\"models\":[{\"name\":\"qwen3-coder:32k\",\"size\":123},{\"name\":\"llama3.2:3b\",\"size\":4}]}"));

        var models = provider.listModels();
        assertEquals(2, models.size());
        assertEquals("qwen3-coder:32k", models.get(0).id());
        assertEquals("ollama", models.get(0).providerId());
    }

    @Test
    void completesChat() throws IOException {
        mock.on("POST", "/api/chat", exchange -> {
            String body = exchange.getRequestBody() == null ? "" : "";
            mock.respond(exchange, 200, "{\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},"
                    + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":10,\"eval_count\":5}");
        });

        ChatResponse response = provider.complete(new ChatRequest("qwen3-coder:32k",
                List.of(ChatMessage.user("hi")), 0.0, 0));
        assertEquals("hello", response.content());
        assertEquals("stop", response.finishReason());
        assertEquals(10, response.promptTokens());
        assertEquals(5, response.completionTokens());

        MockHttpServer.Request request = mock.requests().get(0);
        assertEquals("/api/chat", request.path());
        assertTrue(request.body().contains("\"stream\":false"));
        assertTrue(request.body().contains("qwen3-coder:32k"));
    }

    @Test
    void connectionTestReflectsFailures() {
        // no /api/tags route registered -> 404
        var result = provider.testConnection();
        assertFalse(result.success());
    }

    @Test
    void connectionTestSucceeds() {
        mock.on("GET", "/api/tags", exchange -> mock.respond(exchange, 200,
                "{\"models\":[{\"name\":\"m1\",\"size\":1}]}"));
        assertTrue(provider.testConnection().success());
    }

    @Test
    void serverErrorIsReported() {
        mock.on("POST", "/api/chat", exchange -> mock.respond(exchange, 500, "{\"error\":\"boom\"}"));
        var e = org.junit.jupiter.api.Assertions.assertThrows(
                eu.kalafatic.dcode.config.provider.ProviderException.class,
                () -> provider.complete(new ChatRequest("m", List.of(ChatMessage.user("x")), 0, 0)));
        assertTrue(e.getMessage().contains("500"));
    }
}
