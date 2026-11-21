package eu.kalafatic.dcode.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.core.model.AgentRole;
import eu.kalafatic.dcode.core.model.RepositoryAccessPolicy;
import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.core.model.SessionState;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.core.model.WorkspaceMode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * DCode HTTP API client for JVM clients (the RCP IDE and any other Java consumer).
 * Mirrors the web {@code js/api.js} contract: same endpoints, same error envelope,
 * same authoritativeness. Domain results are mapped onto the shared core model —
 * no client-side duplicates.
 *
 * <p>All network calls are asynchronous (CompletableFuture); callers must marshal
 * results onto their UI thread.</p>
 */
public final class DCodeApiClient {

    private final HttpClient http;
    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile String authToken;

    public DCodeApiClient(String baseUrl) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** Sets the bearer token (held in memory; never persisted by this class). */
    public void setAuthToken(String token) {
        this.authToken = token;
    }

    public CompletableFuture<JsonNode> health() {
        return send("GET", "/api/v1/health", null, Duration.ofSeconds(8));
    }

    // ---- workspaces / repositories ----

    public CompletableFuture<List<Workspace>> listWorkspaces() {
        return get("/api/v1/workspaces").thenApply(root -> {
            List<Workspace> result = new ArrayList<>();
            for (JsonNode node : root) {
                result.add(parseWorkspace(node));
            }
            return result;
        });
    }

    public CompletableFuture<Workspace> createWorkspace(String name, String rootPath) {
        ObjectNode body = mapper.createObjectNode();
        body.put("name", name);
        if (rootPath != null) {
            body.put("rootPath", rootPath);
        }
        return post("/api/v1/workspaces", body).thenApply(this::parseWorkspace);
    }

    public CompletableFuture<Workspace> getWorkspace(String workspaceId) {
        return get("/api/v1/workspaces/" + workspaceId).thenApply(this::parseWorkspace);
    }

    public CompletableFuture<JsonNode> tree(String workspaceId, String path, int depth) {
        StringBuilder query = new StringBuilder("/api/v1/workspaces/" + workspaceId
                + "/tree?depth=" + depth);
        if (path != null && !path.isBlank()) {
            query.append("&path=").append(urlEncode(path));
        }
        return get(query.toString());
    }

    public CompletableFuture<JsonNode> readFile(String workspaceId, String path) {
        return get("/api/v1/workspaces/" + workspaceId + "/files?path=" + urlEncode(path));
    }

    public CompletableFuture<Workspace> addRepository(String workspaceId, String name,
                                                      String localPath, String remoteUrl,
                                                      boolean readOnly) {
        ObjectNode body = mapper.createObjectNode();
        body.put("name", name);
        body.put("localPath", localPath);
        if (remoteUrl != null && !remoteUrl.isBlank()) {
            body.put("remoteUrl", remoteUrl);
        }
        body.put("accessPolicy", readOnly ? "READ_ONLY" : "READ_WRITE");
        return post("/api/v1/workspaces/" + workspaceId + "/repositories", body)
                .thenApply(this::parseWorkspace);
    }

    public CompletableFuture<JsonNode> repositoryStatus(String workspaceId, String repositoryId) {
        return get("/api/v1/workspaces/" + workspaceId + "/repositories/"
                + repositoryId + "/status");
    }

    public CompletableFuture<JsonNode> repositoryDiff(String workspaceId, String repositoryId) {
        return get("/api/v1/workspaces/" + workspaceId + "/repositories/"
                + repositoryId + "/diff");
    }

    public CompletableFuture<JsonNode> repositoryCommit(String workspaceId,
                                                        String repositoryId,
                                                        String message, String accountId) {
        ObjectNode body = mapper.createObjectNode();
        body.put("message", message);
        if (accountId != null) {
            body.put("accountId", accountId);
        }
        return post("/api/v1/workspaces/" + workspaceId + "/repositories/"
                + repositoryId + "/commit", body);
    }

    public CompletableFuture<JsonNode> repositoryPull(String workspaceId, String repositoryId) {
        return post("/api/v1/workspaces/" + workspaceId + "/repositories/"
                + repositoryId + "/pull", mapper.createObjectNode());
    }

    public CompletableFuture<JsonNode> repositoryPush(String workspaceId, String repositoryId) {
        return post("/api/v1/workspaces/" + workspaceId + "/repositories/"
                + repositoryId + "/push", mapper.createObjectNode());
    }

    // ---- sessions ----

    public CompletableFuture<List<Session>> listSessions(String workspaceId) {
        return get("/api/v1/sessions?workspaceId=" + urlEncode(workspaceId))
                .thenApply(root -> {
                    List<Session> result = new ArrayList<>();
                    for (JsonNode node : root) {
                        result.add(parseSession(node));
                    }
                    return result;
                });
    }

    public CompletableFuture<Session> createSession(String title, String workspaceId,
                                                    String providerId, String modelId,
                                                    AgentRole agentRole) {
        return createSession(title, workspaceId, providerId, modelId, agentRole, null, null);
    }

    /**
     * Creates a session with the full selection context: agent and conversation
     * profile are bound by the backend at creation time.
     */
    public CompletableFuture<Session> createSession(String title, String workspaceId,
                                                    String providerId, String modelId,
                                                    AgentRole agentRole, String agentId,
                                                    String profileId) {
        ObjectNode body = mapper.createObjectNode();
        if (title != null && !title.isBlank()) {
            body.put("title", title);
        }
        body.put("workspaceId", workspaceId);
        if (providerId != null) {
            body.put("providerId", providerId);
        }
        if (modelId != null) {
            body.put("modelId", modelId);
        }
        if (agentRole != null) {
            body.put("agentRole", agentRole.name());
        }
        if (agentId != null && !agentId.isBlank()) {
            body.put("agentId", agentId);
        }
        if (profileId != null && !profileId.isBlank()) {
            body.put("profileId", profileId);
        }
        return post("/api/v1/sessions", body).thenApply(this::parseSession);
    }

    /** Renames a session (title must not be blank). */
    public CompletableFuture<Session> renameSession(String sessionId, String title) {
        ObjectNode body = mapper.createObjectNode();
        body.put("title", title);
        return post("/api/v1/sessions/" + sessionId + "/rename", body)
                .thenApply(this::parseSession);
    }

    /** Deletes a session and its message history. Idempotent. */
    public CompletableFuture<JsonNode> deleteSession(String sessionId) {
        return delete("/api/v1/sessions/" + sessionId);
    }

    public CompletableFuture<Session> getSession(String sessionId) {
        return get("/api/v1/sessions/" + sessionId).thenApply(this::parseSession);
    }

    public CompletableFuture<JsonNode> startTask(String sessionId, String task) {
        return startTask(sessionId, task, null, null);
    }

    /** Submits a task with optional per-task provider/model overrides. */
    public CompletableFuture<JsonNode> startTask(String sessionId, String task,
                                                 String providerId, String modelId) {
        return startTask(sessionId, task, providerId, modelId, null, null);
    }

    /**
     * Submits a task with the full selection context: provider/model overrides and the
     * agent/profile bound to the execution.
     */
    public CompletableFuture<JsonNode> startTask(String sessionId, String task,
                                                 String providerId, String modelId,
                                                 String agentId, String profileId) {
        ObjectNode body = mapper.createObjectNode();
        body.put("task", task);
        if (providerId != null && !providerId.isBlank()) {
            body.put("providerId", providerId);
        }
        if (modelId != null && !modelId.isBlank()) {
            body.put("modelId", modelId);
        }
        if (agentId != null && !agentId.isBlank()) {
            body.put("agentId", agentId);
        }
        if (profileId != null && !profileId.isBlank()) {
            body.put("profileId", profileId);
        }
        return post("/api/v1/sessions/" + sessionId + "/start", body);
    }

    public CompletableFuture<JsonNode> cancelSession(String sessionId) {
        return post("/api/v1/sessions/" + sessionId + "/cancel", mapper.createObjectNode());
    }

    public CompletableFuture<JsonNode> messages(String sessionId) {
        return get("/api/v1/sessions/" + sessionId + "/messages");
    }

    public CompletableFuture<JsonNode> reviews(String sessionId) {
        return get("/api/v1/sessions/" + sessionId + "/reviews");
    }

    public CompletableFuture<JsonNode> changes(String sessionId) {
        return get("/api/v1/sessions/" + sessionId + "/changes");
    }

    /** Audit trail of tool executions of a session (ToolEngine record). */
    public CompletableFuture<JsonNode> toolCalls(String sessionId) {
        return get("/api/v1/sessions/" + sessionId + "/toolcalls");
    }

    public CompletableFuture<JsonNode> approveReview(String reviewId, String approver,
                                                      String comment) {
        ObjectNode body = mapper.createObjectNode();
        body.put("approverId", approver);
        if (comment != null) {
            body.put("comment", comment);
        }
        return post("/api/v1/reviews/" + reviewId + "/approve", body);
    }

    public CompletableFuture<JsonNode> rejectReview(String reviewId, String approver,
                                                     String comment) {
        ObjectNode body = mapper.createObjectNode();
        body.put("approverId", approver);
        if (comment != null) {
            body.put("comment", comment);
        }
        return post("/api/v1/reviews/" + reviewId + "/reject", body);
    }

    // ---- providers / runtimes / models / tools ----

    public CompletableFuture<JsonNode> providers() {
        return get("/api/v1/providers");
    }

    public CompletableFuture<JsonNode> models() {
        return get("/api/v1/models");
    }

    public CompletableFuture<JsonNode> models(String runtimeId) {
        return get("/api/v1/models?runtimeId=" + urlEncode(runtimeId));
    }

    public CompletableFuture<JsonNode> tools() {
        return get("/api/v1/tools");
    }

    public CompletableFuture<JsonNode> testProvider(String providerId) {
        return post("/api/v1/providers/" + providerId + "/test", mapper.createObjectNode());
    }

    // ---- runtime management ----

    public CompletableFuture<JsonNode> runtimes() {
        return get("/api/v1/runtimes");
    }

    public CompletableFuture<JsonNode> runtime(String runtimeId) {
        return get("/api/v1/runtimes/" + runtimeId);
    }

    public CompletableFuture<JsonNode> createRuntime(String name, String type,
                                                      String endpoint, String authToken,
                                                      String apiKey) {
        ObjectNode body = mapper.createObjectNode();
        if (name != null && !name.isBlank()) {
            body.put("name", name);
        }
        body.put("type", type);
        body.put("endpoint", endpoint);
        if (authToken != null && !authToken.isBlank()) {
            body.put("authToken", authToken);
        }
        if (apiKey != null && !apiKey.isBlank()) {
            body.put("apiKey", apiKey);
        }
        return post("/api/v1/runtimes", body);
    }

    public CompletableFuture<JsonNode> updateRuntime(String runtimeId, String name,
                                                      String endpoint, String authToken,
                                                      String apiKey) {
        ObjectNode body = mapper.createObjectNode();
        if (name != null) {
            body.put("name", name);
        }
        if (endpoint != null) {
            body.put("endpoint", endpoint);
        }
        if (authToken != null) {
            body.put("authToken", authToken);
        }
        if (apiKey != null) {
            body.put("apiKey", apiKey);
        }
        return post("/api/v1/runtimes/" + runtimeId, body);
    }

    public CompletableFuture<JsonNode> deleteRuntime(String runtimeId) {
        return delete("/api/v1/runtimes/" + runtimeId);
    }

    public CompletableFuture<JsonNode> testRuntime(String runtimeId) {
        return post("/api/v1/runtimes/" + runtimeId + "/test", mapper.createObjectNode());
    }

    /** Connection preflight for a runtime that is not saved yet (credentials not stored). */
    public CompletableFuture<JsonNode> preflightRuntime(String type, String endpoint,
                                                        String authToken, String apiKey) {
        ObjectNode body = mapper.createObjectNode();
        body.put("type", type);
        body.put("endpoint", endpoint);
        if (authToken != null && !authToken.isBlank()) {
            body.put("authToken", authToken);
        }
        if (apiKey != null && !apiKey.isBlank()) {
            body.put("apiKey", apiKey);
        }
        return post("/api/v1/runtimes/preflight", body);
    }

    /** Scans local endpoints for inference services (DCode, ENR, Ollama, OpenAI). */
    public CompletableFuture<JsonNode> scanRuntimes(List<String> endpoints) {
        ObjectNode body = mapper.createObjectNode();
        if (endpoints != null && !endpoints.isEmpty()) {
            ArrayNode array = body.putArray("endpoints");
            endpoints.forEach(array::add);
        }
        return post("/api/v1/runtimes/scan", body);
    }

    /** Coding harnesses known to the backend (availability + integration status). */
    public CompletableFuture<JsonNode> harnesses() {
        return get("/api/v1/harnesses");
    }

    // ---- agents ----

    public CompletableFuture<JsonNode> agents() {
        return get("/api/v1/agents");
    }

    public CompletableFuture<JsonNode> saveAgent(String id, String name, String description,
                                                 String role, String systemInstructions) {
        ObjectNode body = mapper.createObjectNode();
        if (id != null && !id.isBlank()) {
            body.put("id", id);
        }
        body.put("name", name == null ? "" : name);
        body.put("description", description == null ? "" : description);
        body.put("role", role == null ? "MAIN" : role);
        body.put("systemInstructions", systemInstructions == null ? ""
                : systemInstructions);
        return post("/api/v1/agents", body);
    }

    public CompletableFuture<JsonNode> deleteAgent(String agentId) {
        return delete("/api/v1/agents/" + agentId);
    }

    /** Conversation profiles (built-in and custom). */
    public CompletableFuture<JsonNode> profiles() {
        return get("/api/v1/profiles");
    }

    public CompletableFuture<JsonNode> saveProfile(String id, String name,
                                                   String description,
                                                   String systemInstructions) {
        ObjectNode body = mapper.createObjectNode();
        if (id != null && !id.isBlank()) {
            body.put("id", id);
        }
        body.put("name", name);
        body.put("description", description);
        body.put("systemInstructions", systemInstructions);
        return post("/api/v1/profiles", body);
    }

    public CompletableFuture<JsonNode> deleteProfile(String profileId) {
        return delete("/api/v1/profiles/" + profileId);
    }

    public CompletableFuture<JsonNode> setDefaultProfile(String profileId) {
        return post("/api/v1/profiles/" + profileId + "/default", mapper.createObjectNode());
    }

    public CompletableFuture<JsonNode> setDefaultAgent(String agentId) {
        return post("/api/v1/agents/" + agentId + "/default", mapper.createObjectNode());
    }

    // ---- port & network monitor ----

    public CompletableFuture<JsonNode> ports() {
        return get("/api/v1/ports");
    }

    public CompletableFuture<JsonNode> setRuntimeEnabled(String runtimeId, boolean enabled) {
        return post("/api/v1/runtimes/" + runtimeId + "/"
                + (enabled ? "enable" : "disable"), mapper.createObjectNode());
    }

    public CompletableFuture<JsonNode> setDefaultRuntime(String runtimeId, String modelId) {
        ObjectNode body = mapper.createObjectNode();
        if (modelId != null && !modelId.isBlank()) {
            body.put("modelId", modelId);
        }
        return post("/api/v1/runtimes/" + runtimeId + "/default", body);
    }

    public CompletableFuture<JsonNode> runtimeModels(String runtimeId) {
        return get("/api/v1/runtimes/" + runtimeId + "/models");
    }

    public CompletableFuture<JsonNode> modelAction(String runtimeId, String modelId,
                                                   String action) {
        return post("/api/v1/runtimes/" + runtimeId + "/models/"
                + urlEncode(modelId) + "/" + action, mapper.createObjectNode());
    }

    // ---- unified model management (shared ENR infrastructure) ----

    /** GET /api/v1/models/providers */
    public CompletableFuture<JsonNode> modelProviders() {
        return get("/api/v1/models/providers");
    }

    /** GET /api/v1/models/jobs — all shared download jobs (newest first). */
    public CompletableFuture<JsonNode> modelDownloads() {
        return get("/api/v1/models/jobs");
    }

    /** GET /api/v1/models/{provider}/installed */
    public CompletableFuture<JsonNode> modelInstalled(String provider) {
        return get("/api/v1/models/" + urlEncode(provider) + "/installed");
    }

    /** POST /api/v1/models/{provider}/pull — returns the shared download job. */
    public CompletableFuture<JsonNode> modelPull(String provider, String model) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        return post("/api/v1/models/" + urlEncode(provider) + "/pull", body);
    }

    /** GET /api/v1/models/jobs/{jobId} */
    public CompletableFuture<JsonNode> modelJobStatus(String jobId) {
        return get("/api/v1/models/jobs/" + urlEncode(jobId));
    }

    /** DELETE /api/v1/models/jobs/{jobId} */
    public CompletableFuture<JsonNode> modelJobCancel(String jobId) {
        return delete("/api/v1/models/jobs/" + urlEncode(jobId));
    }

    /** DELETE /api/v1/models/{provider}/{modelId} */
    public CompletableFuture<JsonNode> modelRemove(String provider, String modelId) {
        return delete("/api/v1/models/" + urlEncode(provider) + "/" + urlEncode(modelId));
    }

    // ---- direct inference chat (no coding task involved) ----

    public CompletableFuture<JsonNode> inferenceChat(String runtimeId, String model,
                                                     List<Map<String, String>> messages,
                                                     double temperature, int maxTokens) {
        ObjectNode body = mapper.createObjectNode();
        if (runtimeId != null && !runtimeId.isBlank()) {
            body.put("runtimeId", runtimeId);
        }
        body.put("model", model);
        ArrayNode messageArray = body.putArray("messages");
        for (Map<String, String> message : messages) {
            ObjectNode entry = messageArray.addObject();
            entry.put("role", message.getOrDefault("role", "user"));
            entry.put("content", message.getOrDefault("content", ""));
        }
        if (temperature > 0) {
            body.put("temperature", temperature);
        }
        if (maxTokens > 0) {
            body.put("maxTokens", maxTokens);
        }
        return post("/api/v1/inference/chat", body);
    }

    /** Listener of one streamed direct inference generation. */
    public interface InferenceStreamListener {
        void onStart(String requestId, String runtimeId, String model);

        void onToken(String token);

        void onDone(JsonNode result);

        void onError(String message);
    }

    /** Handle to an active streamed generation. */
    public interface InferenceStreamSession {
        String requestId();

        void cancel();
    }

    /**
     * Streams one direct inference generation. Frames are parsed on a background
     * thread; the returned session allows cancellation (closes the HTTP stream).
     */
    public InferenceStreamSession streamInference(String runtimeId, String model,
                                                  List<Map<String, String>> messages,
                                                  double temperature, int maxTokens,
                                                  InferenceStreamListener listener) {
        ObjectNode body = mapper.createObjectNode();
        if (runtimeId != null && !runtimeId.isBlank()) {
            body.put("runtimeId", runtimeId);
        }
        body.put("model", model);
        ArrayNode messageArray = body.putArray("messages");
        for (Map<String, String> message : messages) {
            ObjectNode entry = messageArray.addObject();
            entry.put("role", message.getOrDefault("role", "user"));
            entry.put("content", message.getOrDefault("content", ""));
        }
        if (temperature > 0) {
            body.put("temperature", temperature);
        }
        if (maxTokens > 0) {
            body.put("maxTokens", maxTokens);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl
                        + "/api/v1/inference/chat/stream"))
                .timeout(Duration.ofMinutes(30))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (authToken != null) {
            builder.header("Authorization", "Bearer " + authToken);
        }
        java.util.concurrent.atomic.AtomicReference<InputStream> bodyRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        listener.onError("Stream request failed: "
                                + throwable.getMessage());
                        return;
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        String message;
                        try {
                            JsonNode errorPayload = mapper.readTree(
                                    response.body().readAllBytes());
                            message = errorPayload.path("message").asText(
                                    "HTTP " + response.statusCode());
                        } catch (Exception e) {
                            message = "HTTP " + response.statusCode();
                        }
                        listener.onError(message);
                        return;
                    }
                    bodyRef.set(response.body());
                    Thread reader = new Thread(() -> {
                        try {
                            readSseFrames(response.body(), listener, cancelled,
                                    bodyRef);
                        } catch (IOException e) {
                            if (!cancelled.get()) {
                                listener.onError("Stream read failed: " + e.getMessage());
                            }
                        }
                    }, "dcode-inference-stream");
                    reader.setDaemon(true);
                    reader.start();
                });
        return new InferenceStreamSession() {
            private final java.util.concurrent.atomic.AtomicReference<String> requestId =
                    new java.util.concurrent.atomic.AtomicReference<>();

            @Override
            public String requestId() {
                return requestId.get();
            }

            @Override
            public void cancel() {
                cancelled.set(true);
                InputStream stream = bodyRef.get();
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (Exception ignored) {
                        // best effort abort
                    }
                }
            }
        };
    }

    /** Cancels a streamed generation through the backend cancel endpoint. */
    public CompletableFuture<JsonNode> cancelInference(String requestId) {
        ObjectNode body = mapper.createObjectNode();
        body.put("requestId", requestId);
        return post("/api/v1/inference/cancel", body);
    }

    private static void readSseFrames(InputStream body, InferenceStreamListener listener,
                                      java.util.concurrent.atomic.AtomicBoolean cancelled,
                                      java.util.concurrent.atomic.AtomicReference<InputStream> bodyRef)
            throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body,
                java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            String event = "";
            StringBuilder data = new StringBuilder();
            while (!cancelled.get() && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    if (data.length() > 0 || !event.isEmpty()) {
                        deliver(event, data.toString(), listener);
                    }
                    event = "";
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                switch (field) {
                    case "event" -> event = value;
                    case "data" -> {
                        if (!data.isEmpty()) {
                            data.append('\n');
                        }
                        data.append(value);
                    }
                    default -> {
                    }
                }
            }
        }
    }

    private static void deliver(String event, String data, InferenceStreamListener listener) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            if ("error".equals(event)) {
                JsonNode node = mapper.readTree(data);
                listener.onError(node.path("message").asText("Inference failed"));
                return;
            }
            JsonNode node = mapper.readTree(data);
            if ("start".equals(event)) {
                listener.onStart(node.path("requestId").asText(""),
                        node.path("runtimeId").asText(""), node.path("model").asText(""));
                return;
            }
            if ("done".equals(event)) {
                listener.onDone(node);
                return;
            }
            String token = node.path("token").asText(null);
            if (token != null) {
                listener.onToken(token);
            }
        } catch (Exception e) {
            listener.onError("Invalid stream frame: " + e.getMessage());
        }
    }

    /** Executes a registered tool through the backend ToolEngine. */
    public CompletableFuture<JsonNode> executeTool(String toolName, String sessionId,
                                                   java.util.Map<String, Object> arguments) {
        ObjectNode body = mapper.createObjectNode();
        if (arguments != null) {
            for (var entry : arguments.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String s) {
                    body.put(entry.getKey(), s);
                } else if (value instanceof Number n) {
                    body.put(entry.getKey(), n.doubleValue());
                } else if (value instanceof Boolean b) {
                    body.put(entry.getKey(), b);
                } else {
                    body.put(entry.getKey(), String.valueOf(value));
                }
            }
        }
        body.put("sessionId", sessionId);
        return post("/api/v1/tools/" + toolName + "/execute", body);
    }

    // ---- transport ----

    public CompletableFuture<JsonNode> get(String path) {
        return send("GET", path, null);
    }

    public CompletableFuture<JsonNode> post(String path, JsonNode body) {
        return send("POST", path, body);
    }

    public CompletableFuture<JsonNode> delete(String path) {
        return send("DELETE", path, null);
    }

    private CompletableFuture<JsonNode> send(String method, String path, JsonNode body) {
        return send(method, path, body, null);
    }

    private CompletableFuture<JsonNode> send(String method, String path, JsonNode body,
                                             Duration requestTimeout) {
        CompletableFuture<JsonNode> result = new CompletableFuture<>();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(requestTimeout != null ? requestTimeout
                            : Duration.ofMinutes(30));
            if (authToken != null) {
                builder.header("Authorization", "Bearer " + authToken);
            }
            if (body != null) {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, throwable) -> {
                        if (throwable != null) {
                            result.completeExceptionally(throwable);
                            return;
                        }
                        try {
                            JsonNode payload = mapper.readTree(response.body());
                            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                                result.completeExceptionally(new DCodeApiException(
                                        payload.path("code").asText("HTTP_ERROR"),
                                        payload.path("message").asText(
                                                "HTTP " + response.statusCode()),
                                        response.statusCode()));
                                return;
                            }
                            result.complete(payload);
                        } catch (Exception e) {
                            result.completeExceptionally(e);
                        }
                    });
        } catch (Exception e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    // ---- domain mapping (core model) ----

    private Workspace parseWorkspace(JsonNode node) {
        List<RepositoryRef> repositories = new ArrayList<>();
        for (JsonNode repo : node.path("repositories")) {
            repositories.add(new RepositoryRef(
                    repo.path("id").asText(""),
                    repo.path("name").asText(""),
                    repo.path("localPath").asText(""),
                    repo.path("remoteUrl").asText(null),
                    repo.path("branch").asText(""),
                    "READ_ONLY".equals(repo.path("accessPolicy").asText(""))
                            ? RepositoryAccessPolicy.READ_ONLY
                            : RepositoryAccessPolicy.READ_WRITE,
                    Instant.parse(repo.path("addedAt").asText(Instant.now().toString()))));
        }
        String rootPath = node.hasNonNull("rootPath") ? node.path("rootPath").asText(null) : null;
        return new Workspace(node.path("id").asText(""), node.path("name").asText(""),
                WorkspaceMode.valueOf(node.path("mode").asText("LOCAL")),
                rootPath, repositories,
                Instant.parse(node.path("createdAt").asText(Instant.now().toString())));
    }

    private Session parseSession(JsonNode node) {
        String agentRole = node.path("agentRole").asText(null);
        return Session.builder()
                .id(node.path("id").asText(""))
                .title(node.path("title").asText(""))
                .workspaceId(node.path("workspaceId").asText(""))
                .state(SessionState.valueOf(node.path("state").asText("CREATED")))
                .createdAt(Instant.parse(node.path("createdAt").asText(Instant.now().toString())))
                .updatedAt(Instant.parse(node.path("updatedAt").asText(Instant.now().toString())))
                .providerId(node.path("providerId").asText(null))
                .modelId(node.path("modelId").asText(null))
                .agentRole(agentRole == null ? AgentRole.MAIN : AgentRole.valueOf(agentRole))
                .build();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }
}
