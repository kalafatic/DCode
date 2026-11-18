package eu.kalafatic.dcode.core.port;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * Finds a free TCP port. Prefers the requested port, then probes a bounded range. A
 * successful allocation is verified by actually binding a socket, never by guessing.
 */
public final class PortAllocator {

    private PortAllocator() {
    }

    /** Returns a free port starting at {@code preferred} and probing {@code range} ports. */
    public static int allocate(int preferred, int range) throws IOException {
        if (range < 1) {
            range = 1;
        }
        IOException last = null;
        for (int offset = 0; offset < range; offset++) {
            int candidate = preferred + offset;
            if (candidate < 1 || candidate > 65535) {
                continue;
            }
            try {
                return verifyFree(candidate);
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IOException("No free port found in range " + preferred + ".."
                + (preferred + range - 1), last);
    }

    /** Returns the given port if it is free, otherwise throws. */
    public static int verifyFree(int port) throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(port));
            return port;
        }
    }
}
