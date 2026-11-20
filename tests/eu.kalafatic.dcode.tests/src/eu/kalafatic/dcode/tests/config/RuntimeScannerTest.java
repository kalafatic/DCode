package eu.kalafatic.dcode.tests.config;

import eu.kalafatic.dcode.config.runtime.RuntimeScanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Local inference-service scanner: classifies endpoints by their wire identity
 * (DCode backend, ENR, Ollama, OpenAI-compatible) and reports unreachable services
 * without failing.
 */
class RuntimeScannerTest {

    private MockHttpServer mock;
    private RuntimeScanner scanner;

    @BeforeEach
    void setUp() throws IOException {
        mock = MockHttpServer.start();
        scanner = new RuntimeScanner();
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    @Test
    void detectsDCodeBackend() throws IOException {
        mock.on("GET", "/api/v1/health", exchange -> mock.respond(exchange, 200,
                "{\"application\":\"DCODE\",\"version\":\"2.6.11\",\"ready\":true,"
                        + "\"status\":\"UP\"}"));
        var findings = scanner.scan(List.of(mock.baseUrl()));
        assertEquals(1, findings.size());
        assertEquals("dcode", findings.get(0).kind());
        assertFalse(findings.get(0).isRuntime());
    }

    @Test
    void detectsEnrNativeSurface() throws IOException {
        mock.on("GET", "/api/v1/health", exchange -> mock.respond(exchange, 200,
                "{\"status\":\"healthy\",\"checks\":[]}"));
        mock.on("GET", "/api/v1/status", exchange -> mock.respond(exchange, 200,
                "{\"running\":true,\"version\":\"2.6.11\"}"));
        mock.on("GET", "/api/v1/models", exchange -> mock.respond(exchange, 200,
                "{\"models\":[{\"id\":\"tiny-evo\"},{\"id\":\"evo\"}]}"));
        var findings = scanner.scan(List.of(mock.baseUrl()));
        assertEquals(1, findings.size());
        var finding = findings.get(0);
        assertEquals("enr", finding.kind());
        assertTrue(finding.isRuntime());
        assertEquals(2, finding.modelCount());
        assertTrue(finding.sampleModels().contains("tiny-evo"));
    }

    @Test
    void detectsEnrFamilySurface() throws IOException {
        mock.on("GET", "/enr/v1/health", exchange -> mock.respond(exchange, 200,
                "{\"application\":\"ENR\",\"version\":\"1.0\",\"status\":\"UP\","
                        + "\"ready\":true}"));
        var findings = scanner.scan(List.of(mock.baseUrl()));
        assertEquals(1, findings.size());
        assertEquals("enr", findings.get(0).kind());
    }

    @Test
    void detectsOllama() throws IOException {
        mock.on("GET", "/api/version", exchange -> mock.respond(exchange, 200,
                "{\"version\":\"0.40.16\"}"));
        mock.on("GET", "/api/tags", exchange -> mock.respond(exchange, 200,
                "{\"models\":[{\"name\":\"qwen3-coder-next:32k\"},"
                        + "{\"name\":\"llama3.2:3b\"}]}"));
        var findings = scanner.scan(List.of(mock.baseUrl()));
        assertEquals(1, findings.size());
        var finding = findings.get(0);
        assertEquals("ollama", finding.kind());
        assertTrue(finding.isRuntime());
        assertEquals(2, finding.modelCount());
        assertTrue(finding.description().contains("0.40.16"));
    }

    @Test
    void detectsOpenAiCompatibleApi() throws IOException {
        mock.on("GET", "/v1/models", exchange -> mock.respond(exchange, 200,
                "{\"object\":\"list\",\"data\":[{\"id\":\"deepseek-chat\"}]}"));
        var findings = scanner.scan(List.of(mock.baseUrl()));
        assertEquals(1, findings.size());
        var finding = findings.get(0);
        assertEquals("openai", finding.kind());
        assertTrue(finding.isRuntime());
        assertEquals(1, finding.modelCount());
    }

    @Test
    void unreachableEndpointIsReportedWithoutFailure() {
        var findings = scanner.scan(List.of("http://127.0.0.1:1"));
        assertEquals(1, findings.size());
        assertEquals("unreachable", findings.get(0).kind());
        assertFalse(findings.get(0).reachable());
    }
}
