package eu.kalafatic.dcode.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dcode.core.log.DCodeLog;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * DCode headless application: starts the full server runtime without SWT, without a
 * Workbench and without a display (WP5.8 gate), and provides an ollama/opencode-style
 * command-line interface on top of the same server: {@code status}, {@code runtimes},
 * {@code providers}, {@code models}, {@code sessions}, {@code logs}, {@code web},
 * {@code terminal}, {@code stop}, {@code restart}, {@code help}. Without a subcommand
 * the server runs in the foreground until interrupted. Exit codes: 0 clean, 1 error.
 */
public final class HeadlessApplication implements IApplication {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String[]> LOG_BUFFER = new ArrayList<>();
    private static final int LOG_BUFFER_SIZE = 500;

    private volatile boolean stopped;
    private volatile String subcommand;

    @Override
    public Object start(IApplicationContext context) {
        List<String> args = applicationArgs(context);
        subcommand = args.isEmpty() ? "" : args.get(0);
        String subArgument = args.size() > 1 ? args.get(1) : "";
        // Console output is preserved (default sink) AND the ring buffer for the
        // `logs` subcommand records the same entries.
        DCodeLog.setSink((level, source, message, throwable) -> {
            System.out.println(java.time.Instant.now() + " " + level.name() + " ["
                    + source + "] " + message);
            if (throwable != null) {
                throwable.printStackTrace(System.out);
            }
            rememberLog(level, source, message);
        });
        System.out.println("[DCode] Headless runtime starting (no UI dependencies)");
        if (subcommand.isBlank()) {
            System.out.println("[DCode] Run with an argument for commands: "
                    + "status runtimes providers models sessions logs web terminal "
                    + "stop restart help");
        }
        // The server itself is started by the ServerActivator at the SERVER lifecycle
        // stage; subcommands run against it once the port is published.
        // Subcommands always target the configured (primary) DCode port: a second
        // process issuing `status`/`stop` must observe the running server, not its
        // own transient instance.
        int configuredPort = integerProperty("dcode.port",
                eu.kalafatic.dcode.core.port.DCodePorts.HTTP);
        String endpoint = "http://127.0.0.1:" + configuredPort;
        if (!subcommand.isBlank()) {
            // Wait for this instance to finish binding when it is the primary server.
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline
                    && (System.getProperty("dcode.server.port") == null)
                    && !portOccupied(configuredPort)) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Integer.valueOf(1);
                }
            }
            try {
                return runCommand(subcommand, subArgument, endpoint);
            } catch (Exception e) {
                System.err.println("[DCode] " + subcommand + " failed: " + e.getMessage());
                return Integer.valueOf(1);
            }
        }
        while (!stopped) {
            try {
                synchronized (this) {
                    wait();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println("[DCode] Headless runtime stopped");
        return IApplication.EXIT_OK;
    }

    private Object runCommand(String command, String argument, String endpoint)
            throws Exception {
        switch (command) {
            case "status" -> {
                JsonNode status = getJson(endpoint + "/api/v1/status");
                JsonNode ports = getJson(endpoint + "/api/v1/ports");
                System.out.println("DCode status: ready=" + status.path("ready").asBoolean());
                status.path("stages").properties().forEach(entry ->
                        System.out.println("  " + entry.getKey() + ": "
                                + entry.getValue().asText()));
                for (JsonNode port : ports) {
                    System.out.println("  port " + port.path("id").asText() + ": "
                            + port.path("actualPort").asInt() + " ("
                            + port.path("state").asText() + ")");
                }
            }
            case "runtimes" -> {
                JsonNode runtimes = getJson(endpoint + "/api/v1/runtimes");
                System.out.println("Runtimes (" + runtimes.size() + "):");
                for (JsonNode runtime : runtimes) {
                    System.out.println("  " + runtime.path("name").asText(
                            runtime.path("id").asText("")) + "  " + runtime.path("type")
                            .asText("") + "  " + runtime.path("endpoint").asText("")
                            + "  enabled=" + runtime.path("enabled").asBoolean()
                            + "  default=" + runtime.path("default").asBoolean()
                            + "  reachable=" + runtime.path("status").path("reachable")
                            .asBoolean() + "  models=" + runtime.path("modelCount").asInt(-1)
                            + (runtime.path("status").path("message").asText("").isBlank()
                            ? "" : "  [" + runtime.path("status").path("message").asText("")
                            + "]"));
                }
            }
            case "providers" -> {
                JsonNode providers = getJson(endpoint + "/api/v1/providers");
                System.out.println("Providers (" + providers.size() + "):");
                for (JsonNode provider : providers) {
                    System.out.println("  " + provider.path("name").asText(
                            provider.path("id").asText("")) + "  " + provider.path("type")
                            .asText("") + "  " + provider.path("endpoint").asText(""));
                }
            }
            case "models" -> {
                JsonNode models = getJson(endpoint + "/api/v1/models");
                System.out.println("Models (" + models.size() + "):");
                for (JsonNode model : models) {
                    System.out.println("  " + model.path("id").asText("") + "  runtime="
                            + model.path("runtimeId").asText("") + "  size="
                            + model.path("sizeBytes").asLong(0) + "  available="
                            + model.path("available").asBoolean());
                }
            }
            case "sessions" -> {
                JsonNode workspaces = getJson(endpoint + "/api/v1/workspaces");
                for (JsonNode workspace : workspaces) {
                    JsonNode sessions = getJson(endpoint + "/api/v1/sessions?workspaceId="
                            + java.net.URLEncoder.encode(workspace.path("id").asText(""),
                            java.nio.charset.StandardCharsets.UTF_8));
                    if (sessions.size() > 0) {
                        System.out.println("Workspace " + workspace.path("name").asText(""));
                    }
                    for (JsonNode session : sessions) {
                        System.out.println("  " + session.path("id").asText("") + "  "
                                + session.path("title").asText("") + "  ["
                                + session.path("state").asText("") + "]");
                    }
                }
            }
            case "logs" -> {
                synchronized (LOG_BUFFER) {
                    if (LOG_BUFFER.isEmpty()) {
                        System.out.println("(no buffered log entries)");
                    }
                    for (String[] entry : LOG_BUFFER) {
                        System.out.println(entry[0] + " [" + entry[1] + "] " + entry[2]);
                    }
                }
            }
            case "web" -> openInSystem(endpoint + "/", false);
            case "terminal" -> openInSystem(argument.isBlank() ? "." : argument, true);
            case "stop" -> stopServerProcess();
            case "restart" -> {
                stopServerProcess();
                System.out.println("[DCode] Restart: start DCode again (e.g. the "
                        + "dcode-headless launcher or your service manager) once the "
                        + "port is released.");
            }
            case "help" -> printHelp();
            default -> {
                System.out.println("[DCode] Unknown command: " + command);
                printHelp();
                return Integer.valueOf(1);
            }
        }
        return IApplication.EXIT_OK;
    }

    private static int integerProperty(String name, int defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean portOccupied(int port) {
        return eu.kalafatic.dcode.core.port.PortInspector.occupant(port).isPresent();
    }

    private void stopServerProcess() throws Exception {
        int port = integerProperty("dcode.port",
                eu.kalafatic.dcode.core.port.DCodePorts.HTTP);
        var occupant = eu.kalafatic.dcode.core.port.PortInspector.occupant(port);
        if (occupant.isEmpty()) {
            System.out.println("[DCode] Nothing is listening on port " + port);
            return;
        }
        long pid = occupant.get().pid();
        if (pid == ProcessHandle.current().pid()) {
            System.out.println("[DCode] This process owns port " + port
                    + " — send SIGTERM (Ctrl+C) or run stop from another process.");
            return;
        }
        System.out.println("[DCode] Stopping DCode (pid " + pid + " on port " + port
                + ")…");
        ProcessHandle.of(pid).ifPresent(handle -> {
            handle.destroy();
            try {
                if (!handle.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS).equals(handle)) {
                    // did not exit within 10s: escalate
                    handle.destroyForcibly();
                }
            } catch (Exception ignored) {
                handle.destroyForcibly();
            }
        });
    }

    private static void openInSystem(String target, boolean terminal) {
        String[] command;
        if (terminal) {
            command = new String[]{"x-terminal-emulator", "--working-directory=" + target};
        } else {
            command = new String[]{"xdg-open", target};
        }
        try {
            new ProcessBuilder(command).inheritIO().start();
            System.out.println("[DCode] Opened " + target);
        } catch (Exception e) {
            System.err.println("[DCode] Cannot open " + target + ": " + e.getMessage());
        }
    }

    private static void printHelp() {
        System.out.println("""
                DCode headless commands (against the same backend as RCP and Web):
                  status      backend readiness and lifecycle stages
                  runtimes    runtime registrations with live availability
                  providers   provider listing
                  models      unified model catalogue
                  sessions    sessions per workspace
                  logs        recent buffered log entries
                  web         open the web interface in the system browser
                  terminal    open a system terminal (optionally in a directory)
                  stop        stop the DCode server process on this port
                  restart     stop the server (start it again afterwards)
                  help        this help
                Configuration: -Ddcode.port=N, -Ddcode.config.dir=…, -Ddcode.enr.endpoint=…
                """);
    }

    private static void rememberLog(DCodeLog.Level level, String source, String message) {
        synchronized (LOG_BUFFER) {
            LOG_BUFFER.add(new String[]{Instant.now().toString(),
                    level.name() + "/" + source, message});
            while (LOG_BUFFER.size() > LOG_BUFFER_SIZE) {
                LOG_BUFFER.remove(0);
            }
        }
    }

    private static JsonNode getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from "
                    + url + ": " + response.body());
        }
        return MAPPER.readTree(response.body());
    }

    private static List<String> applicationArgs(IApplicationContext context) {
        Object raw = context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
        if (raw instanceof String[] array) {
            return List.of(array);
        }
        return List.of();
    }

    @Override
    public void stop() {
        stopped = true;
        synchronized (this) {
            notifyAll();
        }
    }
}
