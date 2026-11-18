package eu.kalafatic.dcode.tests.config;

import eu.kalafatic.dcode.config.provider.EnrProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;

/**
 * Live ENR verification for the DCode default inference path (Phase 7 gate).
 *
 * <p>Requires a running ENR server (family surface {@code /enr/v1}). The endpoint is
 * taken from {@code -Ddcode.enr.endpoint} or the family default
 * {@code http://127.0.0.1:28780}. All tests are skipped automatically when ENR is not
 * reachable.</p>
 */
class EnrLiveIntegrationTest {

    private static URI endpoint() {
        String configured = System.getProperty("dcode.enr.endpoint");
        return URI.create(configured != null && !configured.isBlank()
                ? configured : EnrProvider.DEFAULT_BASE_URL);
    }

    private static boolean enrReachable() {
        URI uri = endpoint();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void connectionTestAgainstLiveEnr() {
        Assumptions.assumeTrue(enrReachable(), "ENR not reachable; live test skipped");
        EnrProvider provider = new EnrProvider("enr", "ENR Live", endpoint().toString(),
                Duration.ofSeconds(10));
        var result = provider.testConnection();
        org.junit.jupiter.api.Assertions.assertTrue(result.success(), result.message());
    }

    @Test
    void modelDiscoveryAgainstLiveEnr() {
        Assumptions.assumeTrue(enrReachable(), "ENR not reachable; live test skipped");
        EnrProvider provider = new EnrProvider("enr", "ENR Live", endpoint().toString(),
                Duration.ofSeconds(10));
        var models = provider.listModels();
        org.junit.jupiter.api.Assertions.assertNotNull(models);
        for (var model : models) {
            org.junit.jupiter.api.Assertions.assertEquals("enr", model.providerId());
        }
    }
}
