package eu.kalafatic.dcode.tests.config;

import eu.kalafatic.dcode.config.provider.ChatMessage;
import eu.kalafatic.dcode.config.provider.ChatRequest;
import eu.kalafatic.dcode.config.provider.OllamaProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;

/**
 * Live Ollama verification (WP5.4 gate).
 *
 * <p>Model discovery runs automatically when Ollama is reachable. A full live completion
 * requires an explicit opt-in via {@code -Ddcode.live.model=<name>} (local inference on
 * CPU is too slow for an unconditional suite test).</p>
 */
class LiveOllamaTest {

    private static boolean ollamaReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 11434), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void discoversModelsFromLiveOllama() {
        Assumptions.assumeTrue(ollamaReachable(), "Ollama not reachable; live test skipped");
        OllamaProvider provider = OllamaProvider.localDefault();
        var result = provider.testConnection();
        org.junit.jupiter.api.Assertions.assertTrue(result.success(), result.message());
        var models = provider.listModels();
        org.junit.jupiter.api.Assertions.assertFalse(models.isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(
                models.stream().anyMatch(m -> m.available()),
                "expected at least one chat-capable model");
    }

    @Test
    void completesChatAgainstLiveOllama() {
        String model = System.getProperty("dcode.live.model");
        Assumptions.assumeTrue(model != null && !model.isBlank(),
                "No -Ddcode.live.model set; live completion skipped");
        Assumptions.assumeTrue(ollamaReachable(), "Ollama not reachable; live test skipped");
        OllamaProvider provider = OllamaProvider.localDefault();
        var response = provider.complete(new ChatRequest(model,
                List.of(ChatMessage.user("Reply with exactly: ok")), 0.0, 16));
        org.junit.jupiter.api.Assertions.assertFalse(response.content().isBlank());
    }
}
