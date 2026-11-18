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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
        return get("/api/v1/health");
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
        return post("/api/v1/sessions", body).thenApply(this::parseSession);
    }

    public CompletableFuture<Session> getSession(String sessionId) {
        return get("/api/v1/sessions/" + sessionId).thenApply(this::parseSession);
    }

    public CompletableFuture<JsonNode> startTask(String sessionId, String task) {
        ObjectNode body = mapper.createObjectNode();
        body.put("task", task);
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

    // ---- providers / models / tools ----

    public CompletableFuture<JsonNode> providers() {
        return get("/api/v1/providers");
    }

    public CompletableFuture<JsonNode> models() {
        return get("/api/v1/models");
    }

    public CompletableFuture<JsonNode> tools() {
        return get("/api/v1/tools");
    }

    public CompletableFuture<JsonNode> testProvider(String providerId) {
        return post("/api/v1/providers/" + providerId + "/test", mapper.createObjectNode());
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
        CompletableFuture<JsonNode> result = new CompletableFuture<>();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofMinutes(30));
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
