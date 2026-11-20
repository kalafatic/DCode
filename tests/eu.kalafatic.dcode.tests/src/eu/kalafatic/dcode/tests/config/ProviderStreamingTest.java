package eu.kalafatic.dcode.tests.config;

import eu.kalafatic.dcode.config.provider.ChatMessage;
import eu.kalafatic.dcode.config.provider.ChatRequest;
import eu.kalafatic.dcode.config.provider.EnrProvider;
import eu.kalafatic.dcode.config.provider.OllamaProvider;
import eu.kalafatic.dcode.config.provider.OpenAiCompatibleProvider;
import eu.kalafatic.dcode.config.runtime.StreamHandle;
import eu.kalafatic.dcode.config.runtime.StreamResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streaming contract of the runtime adapters: token-by-token delivery, terminal
 * completion with aggregated result, and cancellation.
 */
class ProviderStreamingTest {

    private MockHttpServer mock;

    @BeforeEach
    void setUp() throws IOException {
        mock = MockHttpServer.start();
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    @Test
    void enrStreamsTokensAndCompletes() throws Exception {
        EnrProvider provider = new EnrProvider("enr", "ENR", mock.baseUrl(),
                Duration.ofSeconds(10));
        mock.on("POST", "/enr/v1/chat/stream", exchange -> mock.respondSse(exchange,
                "data: {\"chunk\":\"hel\",\"engine\":\"enr-native\",\"done\":false}\n\n"
                        + "data: {\"chunk\":\"lo\",\"engine\":\"enr-native\",\"done\":false}\n\n"
                        + "event: done\n"
                        + "data: {\"chunk\":\"\",\"engine\":\"enr-native\",\"done\":true}\n\n"
                        + "data: {\"model\":\"m\",\"finishReason\":\"stop\",\"promptTokens\":3,"
                        + "\"completionTokens\":2}\n\n"));

        StringBuilder tokens = new StringBuilder();
        AtomicReference<StreamResult> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        provider.stream(new ChatRequest("m", List.of(ChatMessage.user("hi")), 0.2, 64),
                listener(tokens, result, latch));
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals("hello", tokens.toString());
        assertEquals("hello", result.get().response().content());
        assertEquals("stop", result.get().response().finishReason());
        assertEquals(2, result.get().response().completionTokens());
    }

    @Test
    void ollamaStreamsTokensAndCompletes() throws Exception {
        OllamaProvider provider = new OllamaProvider("ollama", "Ollama", mock.baseUrl(),
                Duration.ofSeconds(10));
        // Ollama streams NDJSON lines (no SSE framing)
        mock.on("POST", "/api/chat", exchange -> mock.respondSse(exchange,
                "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"ab\"},\"done\":false}\n"
                        + "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"cd\"},\"done\":false}\n"
                        + "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},"
                        + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":4,"
                        + "\"eval_count\":3}\n"));

        StringBuilder tokens = new StringBuilder();
        AtomicReference<StreamResult> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        provider.stream(new ChatRequest("m", List.of(ChatMessage.user("hi")), 0, 64),
                listener(tokens, result, latch));
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals("abcd", tokens.toString());
        assertEquals("abcd", result.get().response().content());
        assertEquals("stop", result.get().response().finishReason());
        assertEquals(4, result.get().response().promptTokens());
    }

    @Test
    void openAiCompatibleStreamsDeltasAndCompletes() throws Exception {
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider("deepseek",
                "DeepSeek", mock.baseUrl(), () -> "sk-test", Duration.ofSeconds(10));
        mock.on("POST", "/chat/completions", exchange -> mock.respondSse(exchange,
                "data: {\"choices\":[{\"delta\":{\"content\":\"one\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\" two\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":5}}\n\n"
                        + "data: [DONE]\n\n"));

        StringBuilder tokens = new StringBuilder();
        AtomicReference<StreamResult> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        provider.stream(new ChatRequest("m", List.of(ChatMessage.user("hi")), 0, 64),
                listener(tokens, result, latch));
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals("one two", tokens.toString());
        assertEquals("one two", result.get().response().content());
        assertEquals("stop", result.get().response().finishReason());
        assertEquals(5, result.get().response().completionTokens());
    }

    @Test
    void cancellationStopsTheStreamAndReportsCancelled() throws Exception {
        EnrProvider provider = new EnrProvider("enr", "ENR", mock.baseUrl(),
                Duration.ofSeconds(10));
        mock.on("POST", "/enr/v1/chat/stream", exchange -> mock.respondSse(exchange,
                "data: {\"chunk\":\"a\",\"engine\":\"enr-native\",\"done\":false}\n\n"
                        + "data: {\"chunk\":\"b\",\"engine\":\"enr-native\",\"done\":false}\n\n"));

        AtomicReference<StreamResult> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        StreamHandle handle = provider.stream(
                new ChatRequest("m", List.of(ChatMessage.user("hi")), 0, 64),
                new eu.kalafatic.dcode.config.runtime.StreamListener() {
                    @Override
                    public void onToken(String text) {
                    }

                    @Override
                    public void onComplete(StreamResult r) {
                        result.set(r);
                        latch.countDown();
                    }

                    @Override
                    public void onError(eu.kalafatic.dcode.config.provider.ProviderException error) {
                        latch.countDown();
                    }
                });
        handle.cancel();
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertTrue(result.get().cancelled());
    }

    private static eu.kalafatic.dcode.config.runtime.StreamListener listener(
            StringBuilder tokens, AtomicReference<StreamResult> result,
            CountDownLatch latch) {
        return new eu.kalafatic.dcode.config.runtime.StreamListener() {
            @Override
            public void onToken(String text) {
                tokens.append(text);
            }

            @Override
            public void onComplete(StreamResult r) {
                result.set(r);
                latch.countDown();
            }

            @Override
            public void onError(eu.kalafatic.dcode.config.provider.ProviderException error) {
                latch.countDown();
            }
        };
    }
}
