package eu.kalafatic.dcode.tests.config;

import eu.kalafatic.dcode.config.provider.ChatMessage;
import eu.kalafatic.dcode.config.provider.ChatRequest;
import eu.kalafatic.dcode.config.provider.OpenAiCompatibleProvider;
import eu.kalafatic.dcode.config.provider.ProviderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleProviderTest {

    private MockHttpServer mock;
    private AtomicReference<String> apiKey;

    @BeforeEach
    void setUp() throws IOException {
        mock = MockHttpServer.start();
        apiKey = new AtomicReference<>("sk-test-secret");
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    private OpenAiCompatibleProvider provider() {
        return new OpenAiCompatibleProvider("deepseek", "DeepSeek Test", mock.baseUrl(),
                apiKey::get, Duration.ofSeconds(5));
    }

    @Test
    void sendsBearerToken() throws IOException {
        mock.on("POST", "/chat/completions", exchange -> mock.respond(exchange, 200,
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                        + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2}}"));

        provider().complete(new ChatRequest("deepseek-chat", List.of(ChatMessage.user("hi")), 0, 0));
        MockHttpServer.Request request = mock.requests().get(0);
        assertEquals("Bearer sk-test-secret", request.headers().get("Authorization"));
    }

    @Test
    void parsesCompletion() throws IOException {
        mock.on("POST", "/chat/completions", exchange -> mock.respond(exchange, 200,
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"done\"},"
                        + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}"));

        var response = provider().complete(new ChatRequest("deepseek-chat",
                List.of(ChatMessage.user("q")), 0.2, 100));
        assertEquals("done", response.content());
        assertEquals(7, response.promptTokens());
    }

    @Test
    void discoversModels() throws IOException {
        mock.on("GET", "/models", exchange -> mock.respond(exchange, 200,
                "{\"data\":[{\"id\":\"deepseek-chat\"},{\"id\":\"deepseek-reasoner\"}]}"));
        var models = provider().listModels();
        assertEquals(2, models.size());
        assertEquals("deepseek-chat", models.get(0).id());
    }

    @Test
    void missingChoicesIsReported() {
        mock.on("POST", "/chat/completions", exchange -> mock.respond(exchange, 200,
                "{\"choices\":[]}"));
        assertThrows(ProviderException.class,
                () -> provider().complete(new ChatRequest("m", List.of(ChatMessage.user("x")), 0, 0)));
    }

    @Test
    void apiKeyNeverLeaksIntoErrors() {
        mock.on("POST", "/chat/completions", exchange -> mock.respond(exchange, 401,
                "{\"error\":\"invalid key\"}"));
        ProviderException e = assertThrows(ProviderException.class,
                () -> provider().complete(new ChatRequest("m", List.of(ChatMessage.user("x")), 0, 0)));
        assertTrue(!e.getMessage().contains("sk-test-secret"));
    }
}
