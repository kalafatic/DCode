package eu.kalafatic.dcode.core.port;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Port inspection utilities: real socket checks and, on Linux, identification of the
 * process occupying a port. Used for collision diagnostics and the port monitor.
 */
public final class PortInspector {

    /** Identifies the process listening on a TCP port (Linux /proc based). */
    public record ProcessInfo(int pid, String command) {
    }

    private PortInspector() {
    }

    /** True when the TCP port is available for binding on the loopback interface. */
    public static boolean available(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Identifies the process occupying a TCP port, when the OS supports it
     * (Linux {@code /proc}). Returns empty on other platforms or when unreadable.
     */
    public static Optional<ProcessInfo> occupant(int port) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux")) {
            return Optional.empty();
        }
        try {
            long inode = tcpListeningInode(port);
            if (inode < 0) {
                return Optional.empty();
            }
            int pid = processByInode(inode);
            if (pid < 0) {
                return Optional.empty();
            }
            return Optional.of(new ProcessInfo(pid, readCommand(pid)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Reads the inode of the LISTEN socket for a port from /proc/net/tcp(6). */
    private static long tcpListeningInode(int port) throws IOException {
        String hexPort = String.format("%04X", port);
        for (String table : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            Path file = Path.of(table);
            if (!Files.isReadable(file)) {
                continue;
            }
            for (String line : Files.readAllLines(file)) {
                String[] fields = line.trim().split("\\s+");
                if (fields.length < 10) {
                    continue;
                }
                String state = fields[3];
                if (!"0A".equalsIgnoreCase(state)) {
                    continue;
                }
                String[] local = fields[1].split(":");
                if (local.length == 2 && hexPort.equalsIgnoreCase(local[1])) {
                    try {
                        return Long.parseLong(fields[9]);
                    } catch (NumberFormatException ignored) {
                        return -1;
                    }
                }
            }
        }
        return -1;
    }

    /** Finds the process that owns a socket inode. */
    private static int processByInode(long inode) throws IOException {
        try (var fds = Files.list(Path.of("/proc"))) {
            for (Path procEntry : fds.toList()) {
                String name = procEntry.getFileName().toString();
                if (!name.chars().allMatch(Character::isDigit)) {
                    continue;
                }
                int pid;
                try {
                    pid = Integer.parseInt(name);
                } catch (NumberFormatException e) {
                    continue;
                }
                Path fdDir = procEntry.resolve("fd");
                if (!Files.isDirectory(fdDir)) {
                    continue;
                }
                try (var fds2 = Files.list(fdDir)) {
                    for (Path fd : fds2.toList()) {
                        try {
                            if (Files.isSymbolicLink(fd)) {
                                Path target = Files.readSymbolicLink(fd);
                                String text = target.toString();
                                if (text.contains("socket:[" + inode + "]")) {
                                    return pid;
                                }
                            }
                        } catch (IOException ignored) {
                            // permission or race: skip this fd
                        }
                    }
                } catch (IOException ignored) {
                    // process exited mid-scan
                }
            }
        }
        return -1;
    }

    private static String readCommand(int pid) {
        try {
            String command = Files.readString(Path.of("/proc", String.valueOf(pid),
                    "comm"));
            if (command == null || command.isBlank()) {
                command = Files.readString(Path.of("/proc", String.valueOf(pid),
                        "cmdline")).replace('\0', ' ').strip();
            }
            return command == null || command.isBlank() ? "(unknown)" : command.strip();
        } catch (IOException e) {
            return "(unreadable)";
        }
    }
}
