package eu.kalafatic.dcode.tests.core;

import eu.kalafatic.dcode.core.port.PortAllocator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortAllocatorTest {

    @Test
    void returnsFreePort() throws IOException {
        int port = PortAllocator.allocate(49080, 10);
        assertTrue(port >= 49080 && port < 49090);
        try (ServerSocket socket = new ServerSocket(port)) {
            assertTrue(socket.isBound());
        }
    }

    @Test
    void skipsOccupiedPort() throws IOException {
        try (ServerSocket occupied = new ServerSocket(49100)) {
            int port = PortAllocator.allocate(49100, 10);
            assertEquals(49101, port);
        }
    }

    @Test
    void failsWhenRangeExhausted() throws IOException {
        try (ServerSocket occupied = new ServerSocket(49150)) {
            assertThrows(IOException.class, () -> PortAllocator.allocate(49150, 1));
        }
    }
}
