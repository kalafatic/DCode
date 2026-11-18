package eu.kalafatic.dcode.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.config.model.ModelDefinition;
import eu.kalafatic.dcode.config.provider.ConnectionTestResult;
import eu.kalafatic.dcode.config.provider.ProviderRegistry;
import eu.kalafatic.dcode.core.config.ConfigurationService;
import eu.kalafatic.dcode.core.event.DCodeEvent;
import eu.kalafatic.dcode.core.event.EventBus;
import eu.kalafatic.dcode.core.lifecycle.DCodeLifecycle;
import eu.kalafatic.dcode.core.model.AgentRole;
import eu.kalafatic.dcode.core.model.MessageRole;
import eu.kalafatic.dcode.core.model.RepositoryAccessPolicy;
import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.WorkspaceMode;
import eu.kalafatic.dcode.core.service.ReviewService;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.core.service.WorkspaceService;
import eu.kalafatic.dcode.harness.execution.SessionExecutor;
import eu.kalafatic.dcode.harness.tool.ToolRegistry;
import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * REST dispatch for the DCode HTTP API. No business logic: every operation delegates to
 * application services; JSON is the only wire format.
 */
public final class DCodeApi {

    private final Supplier<SessionService> sessionSupplier;
    private final Supplier<WorkspaceService> workspaceSupplier;
    private final Supplier<ReviewService> reviewSupplier;
    private final ProviderRegistry providerRegistry;
    private final ToolRegistry toolRegistry;
    private final SessionExecutor sessionExecutor;
    private final DCodeLifecycle lifecycle;
    private final ConfigurationService configuration;
    private final SseHub sseHub;
    private final ObjectMapper mapper = new ObjectMapper();
    private final java.util.function.Supplier<eu.kalafatic.dcode.harness.tool.ToolEngine> toolEngineSupplier;
    private final java.util.function.Supplier<eu.kalafatic.dcode.repositories.service.RepositoryOperations> repositoryOperationsSupplier;
    private final java.util.function.Supplier<eu.kalafatic.dcode.config.git.GitAccountManager> gitAccountsSupplier;

    public DCodeApi(Supplier<SessionService> sessionSupplier,
                    Supplier<WorkspaceService> workspaceSupplier,
                    Supplier<ReviewService> reviewSupplier,
                    ProviderRegistry providerRegistry, ToolRegistry toolRegistry,
                    SessionExecutor sessionExecutor, DCodeLifecycle lifecycle,
                    ConfigurationService configuration, EventBus eventBus) {
        this(sessionSupplier, workspaceSupplier, reviewSupplier, providerRegistry,
                toolRegistry, sessionExecutor, lifecycle, configuration, eventBus,
                () -> null, () -> null, () -> null);
    }

    public DCodeApi(Supplier<SessionService> sessionSupplier,
                    Supplier<WorkspaceService> workspaceSupplier,
                    Supplier<ReviewService> reviewSupplier,
                    ProviderRegistry providerRegistry, ToolRegistry toolRegistry,
                    SessionExecutor sessionExecutor, DCodeLifecycle lifecycle,
                    ConfigurationService configuration, EventBus eventBus,
                    java.util.function.Supplier<eu.kalafatic.dcode.harness.tool.ToolEngine> toolEngineSupplier,
                    java.util.function.Supplier<eu.kalafatic.dcode.repositories.service.RepositoryOperations> repositoryOperationsSupplier,
                    java.util.function.Supplier<eu.kalafatic.dcode.config.git.GitAccountManager> gitAccountsSupplier) {
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.reviewSupplier = Objects.requireNonNull(reviewSupplier, "reviewSupplier");
        this.providerRegistry = Objects.requireNonNull(providerRegistry, "providerRegistry");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.sessionExecutor = sessionExecutor; // optional in embedded/test deployments
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.sseHub = new SseHub(eventBus);
        this.toolEngineSupplier = Objects.requireNonNull(toolEngineSupplier, "toolEngineSupplier");
        this.repositoryOperationsSupplier = Objects.requireNonNull(repositoryOperationsSupplier,
                "repositoryOperationsSupplier");
        this.gitAccountsSupplier = Objects.requireNonNull(gitAccountsSupplier, "gitAccountsSupplier");
    }

    /** Authorization: bearer token required only when a server token is configured. */
    public boolean isAuthorized(String authorizationHeader) {
        var expected = configuration.getSecret("server.authToken");
        if (expected.isEmpty()) {
            return true; // development mode: loopback without a token
        }
        String token = expected.get();
        return authorizationHeader != null
                && authorizationHeader.strip().equals("Bearer " + token);
    }

    /** Opens an SSE stream for one session (or all events when sessionId is null). */
    public InputStream openEventStream(String sessionId) {
        return sseHub.open(sessionId);
    }

    /** Dispatch result: HTTP status + JSON body. */
    public record ApiPayload(int status, String body) {
    }

    public ApiPayload dispatch(String method, String path, NanoHTTPD.IHTTPSession session) {
        String normalized = path.startsWith("/health")
                ? API + "/health" : path;
        try {
            if (method.equals("GET") && normalized.equals(API + "/health")) {
                return payload(health());
            }
            if (!normalized.startsWith(API)) {
                throw new NotFoundException("Unknown route: " + path);
            }
            String route = normalized.substring(API.length());
            String[] parts = route.split("/");
            // route: ["", "workspaces", ...]
            if (parts.length >= 2 && parts[1].equals("status")) {
                return payload(requireGet(method, status()));
            }
            if (parts.length >= 2 && parts[1].equals("workspaces")) {
                return payload(handleWorkspaces(method, parts, session));
            }
            if (parts.length >= 2 && parts[1].equals("sessions")) {
                return payload(handleSessions(method, parts, session));
            }
            if (parts.length >= 2 && parts[1].equals("reviews")) {
                return payload(handleReviews(method, parts, session));
            }
            if (parts.length >= 2 && parts[1].equals("providers")) {
                return payload(handleProviders(method, parts, session));
            }
            if (parts.length >= 2 && parts[1].equals("models")) {
                return payload(requireGet(method, models()));
            }
            if (parts.length >= 2 && parts[1].equals("tools")) {
                if (parts.length == 4 && parts[3].equals("execute") && method.equals("POST")) {
                    return payload(executeTool(parts[2], session));
                }
                return payload(requireGet(method, tools()));
            }
            throw new NotFoundException("Unknown route: " + path);
        } catch (NotFoundException e) {
            return errorPayload(404, "NOT_FOUND", e.getMessage(), false);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return errorPayload(400, "VALIDATION_FAILED", e.getMessage(), false);
        } catch (eu.kalafatic.dcode.core.service.InvalidStateTransitionException e) {
            return errorPayload(409, "CONFLICT", e.getMessage(), false);
        } catch (Exception e) {
            return errorPayload(500, "INTERNAL_ERROR", e.getClass().getSimpleName() + ": "
                    + e.getMessage(), false);
        }
    }

    // ---- workspaces ----

    private String handleWorkspaces(String method, String[] parts,
                                    NanoHTTPD.IHTTPSession session) throws IOException {
        WorkspaceService workspaces = workspaceSupplier.get();
        if (parts.length == 2) {
            if (method.equals("GET")) {
                ArrayNode array = mapper.createArrayNode();
                for (var ws : workspaces.listWorkspaces()) {
                    array.add(workspaceNode(ws));
                }
                return ok(array);
            }
            if (method.equals("POST")) {
                JsonNode body = parseBody(session);
                String name = body.path("name").asText("");
                String rootPath = body.path("rootPath").asText(null);
                if (name.isBlank()) {
                    throw new IllegalArgumentException("Field 'name' is required");
                }
                var ws = workspaces.createWorkspace(name, WorkspaceMode.LOCAL, rootPath);
                return ok(workspaceNode(ws));
            }
        }
        if (parts.length == 3 && method.equals("GET")) {
            var ws = workspaces.getWorkspace(parts[2])
                    .orElseThrow(() -> new NotFoundException("Unknown workspace"));
            return ok(workspaceNode(ws));
        }
        if (parts.length == 4 && parts[3].equals("repositories")) {
            if (method.equals("POST")) {
                JsonNode body = parseBody(session);
                String name = body.path("name").asText("");
                String localPath = body.path("localPath").asText("");
                if (name.isBlank() || localPath.isBlank()) {
                    throw new IllegalArgumentException("Fields 'name' and 'localPath' are required");
                }
                RepositoryRef repository = new RepositoryRef(UUID.randomUUID().toString(),
                        name, localPath, body.path("remoteUrl").asText(null),
                        body.path("branch").asText(""),
                        body.path("accessPolicy").asText("READ_WRITE")
                                .equals("READ_ONLY")
                                ? RepositoryAccessPolicy.READ_ONLY
                                : RepositoryAccessPolicy.READ_WRITE,
                        Instant.now());
                return ok(workspaceNode(workspaces.addRepository(parts[2], repository)));
            }
            if (method.equals("GET")) {
                var ws = workspaces.getWorkspace(parts[2])
                        .orElseThrow(() -> new NotFoundException("Unknown workspace"));
                return ok(workspaceNode(ws));
            }
        }
        if (parts.length == 5 && parts[3].equals("repositories") && method.equals("DELETE")) {
            return ok(workspaceNode(workspaces.removeRepository(parts[2], parts[4])));
        }
        if (parts.length == 6 && parts[3].equals("repositories")) {
            return repositoryOperation(workspaces, parts[2], parts[4], parts[5], method,
                    session);
        }
        if (parts.length == 4 && parts[3].equals("tree") && method.equals("GET")) {
            return tree(workspaces, parts[2], session);
        }
        if (parts.length == 4 && parts[3].equals("files") && method.equals("GET")) {
            return readFile(workspaces, parts[2], session);
        }
        throw new NotFoundException("Unknown route segment");
    }

    // ---- sessions ----

    private String handleSessions(String method, String[] parts,
                                  NanoHTTPD.IHTTPSession session) throws IOException {
        SessionService sessions = sessionSupplier.get();
        if (parts.length == 2) {
            if (method.equals("GET")) {
                String workspaceId = session.getParms().get("workspaceId");
                ArrayNode array = mapper.createArrayNode();
                if (workspaceId != null) {
                    for (var s : sessions.listSessions(workspaceId)) {
                        array.add(sessionNode(s));
                    }
                } else {
                    for (var ws : workspaceSupplier.get().listWorkspaces()) {
                        for (var s : sessions.listSessions(ws.id())) {
                            array.add(sessionNode(s));
                        }
                    }
                }
                return ok(array);
            }
            if (method.equals("POST")) {
                JsonNode body = parseBody(session);
                String workspaceId = body.path("workspaceId").asText(null);
                if (workspaceId == null) {
                    throw new IllegalArgumentException("Field 'workspaceId' is required");
                }
                AgentRole role = null;
                String roleText = body.path("agentRole").asText(null);
                if (roleText != null && !roleText.isBlank()) {
                    try {
                        role = AgentRole.valueOf(roleText);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Unknown agentRole: " + roleText);
                    }
                }
                var created = sessions.createSession(new SessionService.CreateSessionRequest(
                        body.path("title").asText(null), workspaceId,
                        body.path("providerId").asText(null),
                        body.path("modelId").asText(null), role, null));
                return ok(sessionNode(created));
            }
        }
        if (parts.length == 3 && method.equals("GET")) {
            return ok(sessionNode(requireSession(sessions, parts[2])));
        }
        if (parts.length == 4 && parts[3].equals("start") && method.equals("POST")) {
            if (sessionExecutor == null) {
                throw new IllegalStateException("Execution engine is not available");
            }
            JsonNode body = parseBody(session);
            String task = body.path("task").asText(null);
            if (task == null || task.isBlank()) {
                throw new IllegalArgumentException("Field 'task' is required");
            }
            sessionExecutor.submit(parts[2], task);
            return ok(statusNode("accepted", "Execution submitted"));
        }
        if (parts.length == 4 && parts[3].equals("cancel") && method.equals("POST")) {
            if (sessionExecutor == null) {
                throw new IllegalStateException("Execution engine is not available");
            }
            sessionExecutor.cancel(parts[2]);
            return ok(statusNode("accepted", "Cancellation requested"));
        }
        if (parts.length == 4 && parts[3].equals("messages") && method.equals("GET")) {
            ArrayNode array = mapper.createArrayNode();
            for (var message : sessions.listMessages(parts[2])) {
                ObjectNode node = array.addObject();
                node.put("id", message.id());
                node.put("role", message.role().name());
                node.put("content", message.content());
                node.put("sequence", message.sequence());
                node.put("timestamp", message.timestamp().toString());
            }
            return ok(array);
        }
        if (parts.length == 4 && parts[3].equals("context") && method.equals("POST")) {
            return ingestPlatformContext(sessions, parts[2], session);
        }
        if (parts.length == 4 && parts[3].equals("reviews") && method.equals("GET")) {
            ArrayNode array = mapper.createArrayNode();
            for (var review : reviewSupplier.get().reviewsOf(parts[2])) {
                array.add(reviewNode(review));
            }
            return ok(array);
        }
        if (parts.length == 4 && parts[3].equals("changes") && method.equals("GET")) {
            return changes(sessions, parts[2]);
        }
        if (parts.length == 4 && parts[3].equals("toolcalls") && method.equals("GET")) {
            var toolEngine = toolEngineSupplier.get();
            if (toolEngine == null) {
                throw new IllegalStateException("Tool engine is not available");
            }
            ArrayNode array = mapper.createArrayNode();
            for (var call : toolEngine.callsOf(parts[2])) {
                ObjectNode node = array.addObject();
                node.put("id", call.id());
                node.put("toolName", call.toolName());
                node.put("state", call.state().name());
                node.put("errorMessage", call.errorMessage() != null
                        ? call.errorMessage() : "");
                node.put("startedAt", call.startedAt() != null
                        ? call.startedAt().toString() : "");
                node.put("completedAt", call.completedAt() != null
                        ? call.completedAt().toString() : "");
                node.put("argumentsJson", call.argumentsJson() != null
                        ? call.argumentsJson() : "");
                node.put("resultJson", call.resultJson() != null
                        ? call.resultJson() : "");
            }
            return ok(array);
        }
        throw new NotFoundException("Unknown route segment");
    }

    /**
     * Ingests a reconstructed platform context (EVO Cognitive Services) for
     * a session. Persisted as a SYSTEM message with the context id and
     * sections, so it survives restart and participates in history assembly.
     * Transport-neutral: no EVO classes are referenced.
     */
    private String ingestPlatformContext(SessionService sessions, String sessionId,
                                         NanoHTTPD.IHTTPSession session) throws IOException {
        requireSession(sessions, sessionId);
        JsonNode body = parseBody(session);
        String contextId = body.path("contextId").asText(null);
        if (contextId == null || contextId.isBlank()) {
            throw new IllegalArgumentException("Field 'contextId' is required");
        }
        String schemaVersion = body.path("schemaVersion").asText("1.0");
        JsonNode sections = body.path("sections");
        if (sections == null || sections.isMissingNode() || sections.isNull()) {
            throw new IllegalArgumentException("Field 'sections' is required");
        }
        long tokenEstimate = body.path("tokenEstimate").asLong(0);
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("source", "EVO_COGNITIVE_SERVICES");
        envelope.put("contextId", contextId);
        envelope.put("schemaVersion", schemaVersion);
        envelope.put("tokenEstimate", tokenEstimate);
        envelope.set("sections", sections);
        var message = sessions.appendMessage(sessionId, MessageRole.SYSTEM, envelope.toString());
        ObjectNode node = mapper.createObjectNode();
        node.put("accepted", true);
        node.put("messageId", message.id());
        node.put("contextId", contextId);
        node.put("tokenEstimate", tokenEstimate);
        return ok(node);
    }

    // ---- reviews ----

    private String handleReviews(String method, String[] parts,
                                 NanoHTTPD.IHTTPSession session) throws IOException {
        if (parts.length == 4 && (parts[3].equals("approve") || parts[3].equals("reject"))) {
            if (!method.equals("POST")) {
                throw new NotFoundException("Use POST");
            }
            ReviewService reviews = reviewSupplier.get();
            JsonNode body = parseBody(session);
            String approver = body.path("approverId").asText("web");
            String comment = body.path("comment").asText(null);
            var review = parts[3].equals("approve")
                    ? reviews.approve(parts[2], approver, comment)
                    : reviews.reject(parts[2], approver, comment);
            return ok(reviewNode(review));
        }
        if (parts.length == 3 && method.equals("GET")) {
            var review = reviewSupplier.get().find(parts[2])
                    .orElseThrow(() -> new NotFoundException("Unknown review"));
            return ok(reviewNode(review));
        }
        throw new NotFoundException("Unknown route segment");
    }



    // ---- repository git operations (user-initiated, policy-enforced) ----

    private String repositoryOperation(WorkspaceService workspaces, String workspaceId,
                                       String repositoryId, String operation, String method,
                                       NanoHTTPD.IHTTPSession session) {
        var workspace = workspaces.getWorkspace(workspaceId)
                .orElseThrow(() -> new NotFoundException("Unknown workspace"));
        RepositoryRef repository = null;
        for (RepositoryRef candidate : workspace.repositories()) {
            if (candidate.id().equals(repositoryId)) {
                repository = candidate;
            }
        }
        if (repository == null) {
            throw new NotFoundException("Unknown repository: " + repositoryId);
        }
        var repositories = repositoryOperationsSupplier.get();
        if (repositories == null) {
            throw new IllegalStateException("Repository engine is not available");
        }
        switch (operation) {
            case "status" -> {
                if (!method.equals("GET")) {
                    throw new NotFoundException("Use GET");
                }
                var info = repositories.info(workspace, repository);
                var changed = repositories.changedFiles(workspace, repository);
                ObjectNode node = mapper.createObjectNode();
                node.put("name", repository.name());
                node.put("branch", info.branch());
                node.put("remoteUrl", info.remoteUrl() != null ? info.remoteUrl() : "");
                node.put("clean", info.clean());
                node.put("commitCount", info.commitCount());
                node.put("accessPolicy", repository.accessPolicy().name());
                ArrayNode files = node.putArray("changedFiles");
                changed.forEach(files::add);
                node.put("changedCount", changed.size());
                return ok(node);
            }
            case "diff" -> {
                if (!method.equals("GET")) {
                    throw new NotFoundException("Use GET");
                }
                ObjectNode node = mapper.createObjectNode();
                node.put("name", repository.name());
                node.put("diff", repositories.diff(workspace, repository));
                return ok(node);
            }
            case "commit" -> {
                if (!method.equals("POST")) {
                    throw new NotFoundException("Use POST");
                }
                JsonNode body;
                try {
                    body = parseBody(session);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("Cannot parse request body: "
                            + e.getMessage());
                }
                String message = body.path("message").asText(null);
                if (message == null || message.isBlank()) {
                    throw new IllegalArgumentException("Field 'message' is required");
                }
                String authorName = "DCode RCP";
                String authorEmail = "rcp@dcode.local";
                var gitAccounts = gitAccountsSupplier.get();
                String accountId = body.path("accountId").asText(null);
                if (accountId != null && gitAccounts != null) {
                    var account = gitAccounts.find(accountId);
                    if (account.isPresent()) {
                        var identity = gitAccounts.commitIdentity(accountId);
                        authorName = identity.userName();
                        authorEmail = identity.userEmail();
                    }
                }
                try {
                    String hash = repositories.commit(workspace, repository, message,
                            authorName, authorEmail);
                    ObjectNode node = mapper.createObjectNode();
                    node.put("name", repository.name());
                    node.put("commit", hash);
                    return ok(node);
                } catch (eu.kalafatic.dcode.repositories.service.RepositoryAccessException e) {
                    throw new IllegalStateException(e.getMessage());
                }
            }
            case "pull", "push" -> {
                if (!method.equals("POST")) {
                    throw new NotFoundException("Use POST");
                }
                try {
                    if (operation.equals("pull")) {
                        repositories.pull(workspace, repository);
                    } else {
                        repositories.push(workspace, repository);
                    }
                    ObjectNode node = mapper.createObjectNode();
                    node.put("name", repository.name());
                    node.put("operation", operation);
                    node.put("done", true);
                    return ok(node);
                } catch (eu.kalafatic.dcode.repositories.service.RepositoryAccessException e) {
                    throw new IllegalStateException(e.getMessage());
                }
            }
            default -> throw new NotFoundException("Unknown repository operation: "
                    + operation);
        }
    }

    // ---- workspace file browsing (read-only, server-side containment) ----

    private String tree(WorkspaceService workspaces, String workspaceId,
                        NanoHTTPD.IHTTPSession session) {
        var workspace = workspaces.getWorkspace(workspaceId)
                .orElseThrow(() -> new NotFoundException("Unknown workspace"));
        String rootPath = workspace.rootPath();
        if (rootPath == null || rootPath.isBlank()) {
            throw new IllegalStateException("Workspace has no root path");
        }
        java.nio.file.Path root = java.nio.file.Path.of(rootPath);
        String relative = session.getParms().getOrDefault("path", "");
        int depth = Math.min(parseInt(session.getParms().getOrDefault("depth", "2"), 2), 6);
        java.nio.file.Path base;
        try {
            base = relative.isBlank() ? root
                    : eu.kalafatic.dcode.harness.tool.ToolEngine.resolveContained(root, relative);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid path: " + e.getMessage());
        }
        if (!java.nio.file.Files.isDirectory(base)) {
            throw new NotFoundException("Not a directory: " + relative);
        }
        ObjectNode node = mapper.createObjectNode();
        node.put("workspaceId", workspaceId);
        node.put("path", relative);
        ArrayNode entries = node.putArray("entries");
        java.util.List<String[]> listing = new java.util.ArrayList<>();
        collectTree(base, base, depth, listing);
        listing.sort(java.util.Comparator.comparing(a -> (a[1].equals("dir") ? "0" : "1")
                + a[0]));
        for (String[] entry : listing) {
            ObjectNode entryNode = entries.addObject();
            entryNode.put("name", entry[0]);
            entryNode.put("path", entry[2]);
            entryNode.put("type", entry[1]);
            entryNode.put("size", Long.parseLong(entry[3]));
        }
        node.put("entryCount", entries.size());
        return ok(node);
    }

    private void collectTree(java.nio.file.Path base, java.nio.file.Path dir, int depth,
                             java.util.List<String[]> out) {
        if (out.size() >= 500) {
            return;
        }
        try (var stream = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path child : stream.sorted().toList()) {
                if (out.size() >= 500) {
                    return;
                }
                String name = child.getFileName().toString();
                boolean isDirectory = java.nio.file.Files.isDirectory(child);
                if (isDirectory && isSkippedDirectory(name)) {
                    continue;
                }
                String rel = base.relativize(child).toString();
                long size = 0;
                try {
                    size = isDirectory ? 0 : java.nio.file.Files.size(child);
                } catch (Exception ignored) {
                    // unreadable entry: report size 0
                }
                out.add(new String[]{name, isDirectory ? "dir" : "file", rel,
                        String.valueOf(size)});
                if (isDirectory && depth > 1) {
                    collectTree(base, child, depth - 1, out);
                }
            }
        } catch (Exception ignored) {
            // unreadable directory: skip
        }
    }

    private static boolean isSkippedDirectory(String name) {
        return name.equals(".git") || name.equals("target") || name.equals("build")
                || name.equals("node_modules") || name.equals(".mvn") || name.equals("bin")
                || name.equals("out") || name.equals(".settings");
    }

    private String readFile(WorkspaceService workspaces, String workspaceId,
                            NanoHTTPD.IHTTPSession session) {
        var workspace = workspaces.getWorkspace(workspaceId)
                .orElseThrow(() -> new NotFoundException("Unknown workspace"));
        String rootPath = workspace.rootPath();
        if (rootPath == null || rootPath.isBlank()) {
            throw new IllegalStateException("Workspace has no root path");
        }
        String relative = session.getParms().get("path");
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("Query parameter 'path' is required");
        }
        java.nio.file.Path file;
        try {
            file = eu.kalafatic.dcode.harness.tool.ToolEngine.resolveContained(
                    java.nio.file.Path.of(rootPath), relative);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid path: " + e.getMessage());
        }
        if (!java.nio.file.Files.isRegularFile(file)) {
            throw new NotFoundException("No such file: " + relative);
        }
        try {
            long size = java.nio.file.Files.size(file);
            if (size > MAX_FILE_READ_BYTES) {
                throw new IllegalStateException("File too large to view (" + size
                        + " bytes, limit " + MAX_FILE_READ_BYTES + ")");
            }
            ObjectNode node = mapper.createObjectNode();
            node.put("workspaceId", workspaceId);
            node.put("path", relative);
            node.put("size", size);
            node.put("content", java.nio.file.Files.readString(file));
            return ok(node);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read file: " + e.getMessage());
        }
    }

    private static final long MAX_FILE_READ_BYTES = 512 * 1024;

    // ---- tool execution through the single ToolEngine ----

    private String executeTool(String toolName, NanoHTTPD.IHTTPSession session) {
        var toolEngine = toolEngineSupplier.get();
        if (toolEngine == null) {
            throw new IllegalStateException("Tool engine is not available");
        }
        var tool = toolRegistry.find(toolName)
                .orElseThrow(() -> new NotFoundException("Unknown tool: " + toolName));
        JsonNode body;
        try {
            body = parseBody(session);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot parse request body: " + e.getMessage());
        }
        String sessionId = body.path("sessionId").asText(null);
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Field 'sessionId' is required");
        }
        SessionService sessions = sessionSupplier.get();
        var dcodeSession = requireSession(sessions, sessionId);
        var workspace = workspaceSupplier.get().getWorkspace(dcodeSession.getWorkspaceId())
                .orElseThrow(() -> new NotFoundException("Workspace of session not found"));
        if (workspace.rootPath() == null || workspace.rootPath().isBlank()) {
            throw new IllegalStateException("Workspace has no root path");
        }
        var repositories = repositoryOperationsSupplier.get();
        var gitAccounts = gitAccountsSupplier.get();

        boolean mutating = !tool.definition().readOnly();
        var state = dcodeSession.getState();
        if (mutating) {
            switch (state) {
                case CREATED, SUSPENDED, WAITING_FOR_APPROVAL ->
                        dcodeSession = sessions.transition(sessionId,
                                eu.kalafatic.dcode.core.model.SessionState.RUNNING);
                case RUNNING -> { /* already active */ }
                default -> throw new IllegalStateException(
                        "Session " + sessionId + " is " + state
                                + "; mutating tools require a resumable session");
            }
        }

        java.util.Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        body.fields().forEachRemaining(entry -> {
            if (!entry.getKey().equals("sessionId")) {
                arguments.put(entry.getKey(), toPlainValue(entry.getValue()));
            }
        });

        var toolContext = new eu.kalafatic.dcode.harness.tool.ToolContext(
                dcodeSession, workspace, java.nio.file.Path.of(workspace.rootPath()),
                repositories, gitAccounts, () -> false,
                message -> sessions.appendMessage(sessionId,
                        eu.kalafatic.dcode.core.model.MessageRole.SYSTEM, message));
        var call = toolEngine.execute(toolContext, toolName, arguments);

        if (mutating) {
            sessions.transition(sessionId,
                    eu.kalafatic.dcode.core.model.SessionState.SUSPENDED);
        }

        ObjectNode node = mapper.createObjectNode();
        ObjectNode toolCall = node.putObject("toolCall");
        toolCall.put("id", call.id());
        toolCall.put("toolName", call.toolName());
        toolCall.put("state", call.state().name());
        toolCall.put("errorMessage", call.errorMessage() != null ? call.errorMessage() : "");
        toolCall.put("startedAt", call.startedAt().toString());
        toolCall.put("completedAt", call.completedAt() != null
                ? call.completedAt().toString() : "");
        node.put("output", call.resultJson() != null ? call.resultJson() : "");
        ObjectNode result = mapper.createObjectNode();
        try {
            JsonNode wrapper = call.resultJson() != null && !call.resultJson().isBlank()
                    ? mapper.readTree(call.resultJson()) : mapper.createObjectNode();
            result.put("success", wrapper.path("success").asBoolean(false));
            result.put("error", wrapper.path("error").asText(""));
            // structured tools return their payload as a nested JSON string; unwrap it
            // so API clients (the web UI) can consume structured fields directly.
            JsonNode output = wrapper.path("output");
            if (output.isTextual()) {
                String outputText = output.asText();
                try {
                    result.set("output", outputText.isBlank()
                            ? mapper.createObjectNode() : mapper.readTree(outputText));
                } catch (Exception parseFailure) {
                    result.put("output", outputText);
                }
            } else {
                result.set("output", output);
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", String.valueOf(e.getMessage()));
        }
        node.set("result", result);
        return ok(node);
    }

    private static Object toPlainValue(JsonNode value) {
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isInt()) {
            return value.asInt();
        }
        if (value.isLong()) {
            return value.asLong();
        }
        if (value.isDouble() || value.isFloat()) {
            return value.asDouble();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return value.toString();
    }

    // ---- live per-repository changes + diffs ----

    private String changes(SessionService sessions, String sessionId) {
        var dcodeSession = requireSession(sessions, sessionId);
        var workspace = workspaceSupplier.get().getWorkspace(dcodeSession.getWorkspaceId())
                .orElseThrow(() -> new NotFoundException("Workspace of session not found"));
        var repositories = repositoryOperationsSupplier.get();
        if (repositories == null) {
            throw new IllegalStateException("Repository engine is not available");
        }
        ObjectNode node = mapper.createObjectNode();
        node.put("sessionId", sessionId);
        node.put("state", dcodeSession.getState().name());
        ArrayNode repositoryNodes = node.putArray("repositories");
        for (var repository : workspace.repositories()) {
            try {
                var info = repositories.info(workspace, repository);
                var changed = repositories.changedFiles(workspace, repository);
                ObjectNode repositoryNode = repositoryNodes.addObject();
                repositoryNode.put("name", repository.name());
                repositoryNode.put("branch", info.branch());
                repositoryNode.put("accessPolicy", repository.accessPolicy().name());
                repositoryNode.put("clean", info.clean());
                ArrayNode files = repositoryNode.putArray("changedFiles");
                changed.forEach(files::add);
                repositoryNode.put("changedCount", changed.size());
                repositoryNode.put("diff", repositories.diff(workspace, repository));
            } catch (Exception e) {
                ObjectNode repositoryNode = repositoryNodes.addObject();
                repositoryNode.put("name", repository.name());
                repositoryNode.put("error", String.valueOf(e.getMessage()));
            }
        }
        return ok(node);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    // ---- providers/models/tools ----

    private String handleProviders(String method, String[] parts,
                                   NanoHTTPD.IHTTPSession session) {
        if (parts.length == 2 && method.equals("GET")) {
            ArrayNode array = mapper.createArrayNode();
            for (var provider : providerRegistry.providers()) {
                ObjectNode node = array.addObject();
                node.put("id", provider.providerId());
                node.put("name", provider.displayName());
                node.put("baseUrl", provider.baseUrl());
                node.put("local", provider.isLocal());
            }
            return ok(array);
        }
        if (parts.length == 4 && parts[3].equals("test") && method.equals("POST")) {
            ConnectionTestResult result = providerRegistry.testConnection(parts[2]);
            ObjectNode node = mapper.createObjectNode();
            node.put("providerId", parts[2]);
            node.put("success", result.success());
            node.put("message", result.message());
            return ok(node);
        }
        throw new NotFoundException("Unknown route segment");
    }

    private String models() {
        ArrayNode array = mapper.createArrayNode();
        for (ModelDefinition model : providerRegistry.listAllModels()) {
            ObjectNode node = array.addObject();
            node.put("id", model.id());
            node.put("name", model.name());
            node.put("providerId", model.providerId());
            node.put("contextWindowTokens", model.contextWindowTokens());
            node.put("available", model.available());
        }
        return ok(array);
    }

    private String tools() {
        ArrayNode array = mapper.createArrayNode();
        for (var definition : toolRegistry.definitions()) {
            ObjectNode node = array.addObject();
            node.put("name", definition.name());
            node.put("description", definition.description());
            node.put("readOnly", definition.readOnly());
            node.put("scope", definition.scope().name());
            node.put("parameters", definition.parametersJson());
        }
        return ok(array);
    }

    private String health() {
        ObjectNode node = mapper.createObjectNode();
        node.put("application", "DCODE");
        node.put("version", "0.1.0");
        node.put("ready", lifecycle.isReady());
        node.put("status", lifecycle.isReady() ? "UP" : "DEGRADED");
        node.put("timestamp", Instant.now().toString());
        ObjectNode stages = node.putObject("stages");
        for (eu.kalafatic.dcode.core.lifecycle.LifecycleStage stage
                : eu.kalafatic.dcode.core.lifecycle.LifecycleStage.values()) {
            stages.put(stage.name(), lifecycle.getStageState(stage).name());
        }
        return ok(node);
    }

    private String status() {
        ObjectNode node = mapper.createObjectNode();
        node.put("ready", lifecycle.isReady());
        ObjectNode stages = node.putObject("stages");
        for (eu.kalafatic.dcode.core.lifecycle.LifecycleStage stage
                : eu.kalafatic.dcode.core.lifecycle.LifecycleStage.values()) {
            stages.put(stage.name(), lifecycle.getStageState(stage).name());
        }
        return ok(node);
    }

    // ---- helpers ----

    private static final String API = "/api/v1";

    private static String requireGet(String method, String payload) {
        if (!method.equals("GET")) {
            throw new NotFoundException("Use GET");
        }
        return payload;
    }

    private JsonNode parseBody(NanoHTTPD.IHTTPSession session) throws IOException {
        String body = DCodeHttpServer.readBody(session);
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(body);
    }

    private eu.kalafatic.dcode.core.model.Session requireSession(SessionService sessions,
                                                                 String id) {
        return sessions.getSession(id)
                .orElseThrow(() -> new NotFoundException("Unknown session: " + id));
    }

    private ObjectNode workspaceNode(eu.kalafatic.dcode.core.model.Workspace ws) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", ws.id());
        node.put("name", ws.name());
        node.put("mode", ws.mode().name());
        node.put("rootPath", ws.rootPath() != null ? ws.rootPath() : "");
        node.put("createdAt", ws.createdAt().toString());
        ArrayNode repositories = node.putArray("repositories");
        for (RepositoryRef repo : ws.repositories()) {
            ObjectNode repoNode = repositories.addObject();
            repoNode.put("id", repo.id());
            repoNode.put("name", repo.name());
            repoNode.put("localPath", repo.localPath());
            repoNode.put("remoteUrl", repo.remoteUrl() != null ? repo.remoteUrl() : "");
            repoNode.put("branch", repo.branch());
            repoNode.put("accessPolicy", repo.accessPolicy().name());
        }
        return node;
    }

    private ObjectNode sessionNode(eu.kalafatic.dcode.core.model.Session s) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", s.getId());
        node.put("title", s.getTitle());
        node.put("workspaceId", s.getWorkspaceId());
        node.put("state", s.getState().name());
        node.put("createdAt", s.getCreatedAt().toString());
        node.put("updatedAt", s.getUpdatedAt().toString());
        node.put("providerId", s.getProviderId() != null ? s.getProviderId() : "");
        node.put("modelId", s.getModelId() != null ? s.getModelId() : "");
        node.put("agentRole", s.getAgentRole().name());
        return node;
    }

    private ObjectNode reviewNode(ReviewService.Review review) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", review.id());
        node.put("sessionId", review.sessionId());
        node.put("changeSetId", review.changeSetId());
        node.put("status", review.status().name());
        node.put("approverId", review.approverId() != null ? review.approverId() : "");
        node.put("comment", review.comment() != null ? review.comment() : "");
        node.put("createdAt", review.createdAt().toString());
        if (review.decidedAt() != null) {
            node.put("decidedAt", review.decidedAt().toString());
        }
        return node;
    }

    private ObjectNode statusNode(String status, String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", status);
        node.put("message", message);
        return node;
    }

    String ok(JsonNode node) {
        return node.toString();
    }

    private ApiPayload payload(String body) {
        return new ApiPayload(200, body);
    }

    private ApiPayload errorPayload(int status, String code, String message, boolean retryable) {
        return new ApiPayload(status, error(code, message, null, retryable));
    }

    /** Top-level error envelope (family TEIP error model shape). */
    public String error(String code, String message, String requestId, boolean retryable) {
        ObjectNode node = mapper.createObjectNode();
        node.put("code", code);
        node.put("message", message != null ? message : "");
        node.put("retryable", retryable);
        node.put("timestamp", Instant.now().toString());
        return node.toString();
    }

    /** 404 inside dispatch. */
    public static final class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
