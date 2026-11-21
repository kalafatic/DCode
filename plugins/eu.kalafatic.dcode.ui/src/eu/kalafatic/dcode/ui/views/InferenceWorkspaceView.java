package eu.kalafatic.dcode.ui.views;

import com.fasterxml.jackson.databind.JsonNode;
import eu.kalafatic.dcode.client.BackendIdentity;
import eu.kalafatic.dcode.client.ConnectionState;
import eu.kalafatic.dcode.client.DCodeApiClient;
import eu.kalafatic.dcode.client.DCodeSseClient;
import eu.kalafatic.dcode.core.model.AgentRole;
import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.inference.ui.ChatWorkspaceBackend;
import eu.kalafatic.dcode.inference.ui.ChatWorkspaceComposite;
import eu.kalafatic.dcode.inference.ui.ChatWorkspaceUi;
import eu.kalafatic.dcode.inference.ui.model.MessageKind;
import eu.kalafatic.dcode.inference.ui.model.SearchResult;
import eu.kalafatic.dcode.inference.ui.model.SelectOption;
import eu.kalafatic.dcode.inference.ui.model.SelectorSpec;
import eu.kalafatic.dcode.inference.ui.model.SessionInfo;
import eu.kalafatic.dcode.inference.ui.model.StatusKind;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The DCode inference workspace: an OpenCode-like AI coding chat over the DCode
 * session/agent execution backend.
 *
 * <p>Conversation execution is session-based: sending a prompt creates a session when
 * needed and submits the task to the native agent loop; assistant output streams
 * token-by-token via {@code MESSAGE_PARTIAL} SSE events and is finalized by
 * {@code MESSAGE_APPENDED}. Stop cancels the running execution; sessions are
 * multi-turn and fully persisted server-side.</p>
 *
 * <p>All selectors use real backend registries: workspaces, runtimes (providers),
 * runtime models, agents and conversation profiles. The search selector enables the
 * backend {@code search_code} tool. No Git functionality is used anywhere.</p>
 */
public final class InferenceWorkspaceView extends ViewPart
        implements ChatWorkspaceBackend, RuntimeConnection.ConnectionStateListener,
        Ui.SseEventListener, Ui.SessionListener {

    public static final String VIEW_ID = "eu.kalafatic.dcode.ui.views.inference";

    private static final int MAX_ATTACHMENT_BYTES = 200 * 1024;
    private static final DateTimeFormatter TITLE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private ChatWorkspaceComposite composite;
    private ChatWorkspaceUi ui;

    private String projectId;
    private String providerId;
    private String modelId;
    private String agentId;
    private String agentRoleName;
    private String profileId;
    private Session activeSession;
    private String streamingEntryId;
    private boolean busy;
    private final Set<String> renderedMessageIds = new HashSet<>();
    private boolean disposed;

    @Override
    public void createPartControl(Composite parent) {
        composite = new ChatWorkspaceComposite(parent, this);
        ui = composite;
        RuntimeConnection.instance().addStateListener(this);
        Ui.addSseEventListener(this);
        Ui.addSessionListener(this);
    }

    // ------------------------------------------------------- ChatWorkspaceBackend

    @Override
    public void init(ChatWorkspaceUi ui) {
        this.ui = ui;
        installSelectorSet();
        Ui.async(this::refreshConfiguration);
    }

    @Override
    public void refreshConfiguration() {
        if (disposed) {
            return;
        }
        if (!RuntimeConnection.instance().isUsableForRequests()) {
            ui.setStatus("Backend unavailable \u2014 check Connection (Window \u2192"
                    + " Show View \u2192 Connection)", StatusKind.ERROR);
            return;
        }
        ui.setStatus("Loading configuration\u2026", StatusKind.INFO);
        loadProjects();
        loadProviders();
        loadAgents();
        loadProfiles();
        refreshSessions();
    }

    private void loadProjects() {
        Ui.run(api().listWorkspaces(), workspaces -> {
            List<SelectOption> options = new ArrayList<>();
            for (Workspace workspace : workspaces) {
                options.add(new SelectOption(workspace.id(), workspace.name(),
                        workspace.rootPath() == null ? "" : workspace.rootPath(),
                        false));
            }
            ui.updateSelectorOptions("project", options, projectId);
            if (projectId == null && !options.isEmpty()) {
                projectId = options.get(0).id();
                ui.updateSelectorOptions("project", options, projectId);
                refreshSessions();
            }
        }, "list workspaces");
    }

    private void loadProviders() {
        Ui.run(api().runtimes(), list -> {
            List<SelectOption> options = new ArrayList<>();
            String defaultRuntime = null;
            for (JsonNode runtime : list) {
                String id = runtime.path("id").asText("");
                String name = runtime.path("name").asText(id);
                boolean enabled = runtime.path("enabled").asBoolean(true);
                boolean isDefault = runtime.path("default").asBoolean(false);
                String label = enabled ? name : name + " (disabled)";
                options.add(new SelectOption(id, label,
                        runtime.path("type").asText(""), enabled && isDefault));
                if (enabled && isDefault) {
                    defaultRuntime = id;
                }
            }
            if (providerId == null || options.stream().noneMatch(
                    option -> option.id().equals(providerId))) {
                providerId = defaultRuntime != null ? defaultRuntime
                        : options.stream().filter(SelectOption::recommended).findFirst()
                        .map(SelectOption::id)
                        .orElse(options.isEmpty() ? null : options.get(0).id());
            }
            ui.updateSelectorOptions("provider", options, providerId);
            if (providerId == null) {
                ui.updateSelectorOptions("model", List.of(), null);
                ui.setStatus("No providers \u2014 register a runtime first (Window \u2192"
                        + " Show View \u2192 Runtime & Models)", StatusKind.ERROR);
            } else {
                loadModels();
            }
        }, "list runtimes");
    }

    private void loadModels() {
        if (providerId == null) {
            return;
        }
        String currentProvider = providerId;
        Ui.run(api().runtimeModels(currentProvider), models -> {
            List<SelectOption> options = new ArrayList<>();
            for (JsonNode model : models) {
                String id = model.path("id").asText("");
                options.add(new SelectOption(id, id, "", false));
            }
            if (modelId == null || options.stream().noneMatch(
                    option -> option.id().equals(modelId))) {
                modelId = options.isEmpty() ? null : options.get(0).id();
            }
            ui.updateSelectorOptions("model", options, modelId);
            if (options.isEmpty()) {
                ui.setStatus("No models published by '" + currentProvider + "'",
                        StatusKind.INFO);
            }
        }, "list models of " + currentProvider);
    }

    private void loadAgents() {
        Ui.run(api().agents(), list -> {
            List<SelectOption> options = new ArrayList<>();
            for (JsonNode agent : list) {
                String id = agent.path("id").asText("");
                options.add(new SelectOption(id, agent.path("name").asText(id),
                        agent.path("description").asText(""),
                        agent.path("default").asBoolean(false)));
            }
            if (agentId == null || options.stream().noneMatch(
                    option -> option.id().equals(agentId))) {
                SelectOption selected = options.stream()
                        .filter(SelectOption::recommended).findFirst()
                        .orElse(options.isEmpty() ? null : options.get(0));
                agentId = selected == null ? null : selected.id();
            }
            ui.updateSelectorOptions("agent", options, agentId);
            rememberAgentRole(list);
        }, "list agents");
    }

    private void rememberAgentRole(JsonNode agents) {
        for (JsonNode agent : agents) {
            if (agent.path("id").asText("").equals(agentId)) {
                agentRoleName = agent.path("role").asText("MAIN");
                return;
            }
        }
        agentRoleName = "MAIN";
    }

    private void loadProfiles() {
        Ui.run(api().profiles(), list -> {
            List<SelectOption> options = new ArrayList<>();
            for (JsonNode profile : list) {
                String id = profile.path("id").asText("");
                options.add(new SelectOption(id, profile.path("name").asText(id),
                        profile.path("description").asText(""),
                        profile.path("default").asBoolean(false)));
            }
            if (profileId == null || options.stream().noneMatch(
                    option -> option.id().equals(profileId))) {
                SelectOption selected = options.stream()
                        .filter(SelectOption::recommended).findFirst()
                        .orElse(options.isEmpty() ? null : options.get(0));
                profileId = selected == null ? null : selected.id();
            }
            ui.updateSelectorOptions("profile", options, profileId);
        }, "list profiles");
    }

    private void installSelectorSet() {
        List<SelectorSpec> specs = List.of(
                new SelectorSpec("project", "Project", List.of(), null,
                        "Workspace project the session operates on", true),
                new SelectorSpec("provider", "Provider", List.of(), null,
                        "Inference runtime/provider used for execution"),
                new SelectorSpec("model", "Model", List.of(), null,
                        "Model served by the selected provider"),
                new SelectorSpec("agent", "Agent", List.of(), null,
                        "Agent bound to new sessions"),
                new SelectorSpec("profile", "Profile", List.of(), null,
                        "Conversation profile applied to the execution context"),
                new SelectorSpec(ChatWorkspaceComposite.SEARCH_SELECTOR, "Search",
                        List.of(new SelectOption(ChatWorkspaceComposite.SEARCH_DISABLED,
                                "Disabled", "No search"),
                                new SelectOption("workspace", "Workspace search",
                                        "Regex search over workspace files (search_code tool)")),
                        ChatWorkspaceComposite.SEARCH_DISABLED,
                        "Search integration for the workspace"));
        ui.setSelectors(specs);
    }

    @Override
    public void selectorChanged(String selectorId, String optionId) {
        switch (selectorId) {
            case "project" -> {
                if (optionId != null) {
                    projectId = optionId;
                    refreshSessions();
                }
            }
            case "provider" -> {
                if (optionId != null && !optionId.equals(providerId)) {
                    providerId = optionId;
                    modelId = null;
                    loadModels();
                }
            }
            case "model" -> modelId = optionId;
            case "agent" -> {
                if (optionId != null) {
                    agentId = optionId;
                    Ui.run(api().agents(), this::rememberAgentRole, "list agents");
                }
            }
            case "profile" -> {
                if (optionId != null) {
                    profileId = optionId;
                    ui.setStatus("Profile: " + optionId
                            + " (applied to the next submission)", StatusKind.INFO);
                }
            }
            default -> { /* search visibility handled by the presentation */ }
        }
    }

    @Override
    public void sendMessage(String text, List<String> attachmentPaths) {
        if (busy) {
            return;
        }
        CompletableFuture.supplyAsync(() -> buildPrompt(text, attachmentPaths))
                .thenAccept(prompt -> Ui.async(() -> submit(prompt)));
    }

    private static String buildPrompt(String text, List<String> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return text;
        }
        StringBuilder prompt = new StringBuilder();
        for (String path : attachments) {
            Path file = Path.of(path);
            prompt.append("[Attached file: ").append(file.getFileName()).append("]\n");
            try {
                if (Files.size(file) > MAX_ATTACHMENT_BYTES) {
                    prompt.append("(file too large to attach inline; the agent can read it"
                            + " from the workspace)\n\n");
                    continue;
                }
                String content = Files.readString(file, StandardCharsets.UTF_8);
                prompt.append("```\n").append(content).append("\n```\n\n");
            } catch (IOException e) {
                prompt.append("(unreadable file: ").append(e.getMessage()).append(")\n\n");
            }
        }
        prompt.append(text);
        return prompt.toString();
    }

    private void submit(String prompt) {
        if (!RuntimeConnection.instance().isUsableForRequests()) {
            ui.addMessage(MessageKind.ERROR, "Error", "Backend unavailable");
            ui.setStatus("Backend unavailable", StatusKind.ERROR);
            return;
        }
        if (projectId == null) {
            ui.addMessage(MessageKind.ERROR, "Error",
                    "No project selected. Create a workspace first (Workspace Explorer).");
            ui.setStatus("No project selected", StatusKind.ERROR);
            return;
        }
        if (activeSession != null) {
            start(activeSession, prompt);
            return;
        }
        String title = "Session " + TITLE_TIME.format(Instant.now());
        ui.setStatus("Creating session\u2026", StatusKind.BUSY);
        Ui.run(api().createSession(title, projectId, providerId, modelId,
                        parseAgentRole(), agentId, profileId),
                session -> {
                    activate(session);
                    start(session, prompt);
                },
                "create session");
    }

    private void start(Session session, String prompt) {
        busy = true;
        ui.setBusy(true);
        ui.setStatus("Running on " + nvl(session.getProviderId(), providerId)
                + " / " + nvl(session.getModelId(), modelId), StatusKind.BUSY);
        streamingEntryId = ui.addStreamingMessage(MessageKind.ASSISTANT, "Assistant");
        String sessionId = session.getId();
        Ui.run(api().startTask(sessionId, prompt, providerId, modelId, agentId,
                profileId),
                json -> { /* execution runs asynchronously; SSE drives the UI */ },
                "submit task", failure -> failGeneration("Submission failed: "
                        + String.valueOf(failure.getMessage())));
    }

    private void failGeneration(String message) {
        busy = false;
        ui.setBusy(false);
        if (streamingEntryId != null) {
            ui.cancelStreaming(streamingEntryId);
            streamingEntryId = null;
        }
        ui.addMessage(MessageKind.ERROR, "Error", message);
        ui.setStatus(message, StatusKind.ERROR);
    }

    @Override
    public void stopGeneration() {
        if (!busy || activeSession == null) {
            return;
        }
        ui.setStatus("Cancellation requested\u2026", StatusKind.INFO);
        Ui.run(api().cancelSession(activeSession.getId()),
                json -> { /* state change arrives via SSE */ },
                "cancel session");
    }

    @Override
    public void createSession() {
        if (projectId == null) {
            ui.setStatus("No project selected", StatusKind.ERROR);
            return;
        }
        String title = "Session " + TITLE_TIME.format(Instant.now());
        Ui.run(api().createSession(title, projectId, providerId, modelId,
                        parseAgentRole(), agentId, profileId),
                this::activate, "create session");
    }

    @Override
    public void openSession(String sessionId) {
        Ui.run(api().getSession(sessionId), this::activate, "open session");
    }

    @Override
    public void sessionChanged(Session session) {
        if (session == null || activeSession != null
                && activeSession.getId().equals(session.getId())) {
            return;
        }
        Ui.async(() -> openSession(session.getId()));
    }

    private void activate(Session session) {
        activeSession = session;
        RuntimeConnection.instance().setActiveSession(session);
        Ui.connectSessionStream(session);
        ui.clearConversation();
        renderedMessageIds.clear();
        streamingEntryId = null;
        renderAllMessages();
        switch (session.getState()) {
            case RUNNING -> {
                busy = true;
                ui.setBusy(true);
                ui.setStatus("Session running…", StatusKind.BUSY);
                streamingEntryId = ui.addStreamingMessage(MessageKind.ASSISTANT, "Assistant");
            }
            case WAITING_FOR_APPROVAL -> ui.setStatus("Session: " + session.getTitle()
                    + " — changes pending review", StatusKind.INFO);
            default -> ui.setStatus("Session: " + session.getTitle(), StatusKind.NONE);
        }
        refreshSessions();
    }

    @Override
    public void renameSession(String sessionId, String newTitle) {
        Ui.run(api().renameSession(sessionId, newTitle), session -> {
            if (activeSession != null && activeSession.getId().equals(session.getId())) {
                activeSession = session;
                ui.setStatus("Session: " + session.getTitle(), StatusKind.NONE);
            }
            refreshSessions();
        }, "rename session");
    }

    @Override
    public void deleteSession(String sessionId) {
        Ui.run(api().deleteSession(sessionId), json -> {
            if (activeSession != null && activeSession.getId().equals(sessionId)) {
                activeSession = null;
                RuntimeConnection.instance().setActiveSession(null);
                ui.clearConversation();
                renderedMessageIds.clear();
                streamingEntryId = null;
                ui.setStatus("Session deleted", StatusKind.NONE);
            }
            refreshSessions();
        }, "delete session");
    }

    @Override
    public void refreshSessions() {
        if (projectId == null) {
            ui.setSessions(List.of(), null);
            return;
        }
        Ui.run(api().listSessions(projectId), sessions -> {
            List<SessionInfo> infos = new ArrayList<>();
            for (Session session : sessions) {
                infos.add(SessionInfo.of(session.getId(), session.getTitle(),
                        session.getState().name(), session.getUpdatedAt()));
            }
            ui.setSessions(infos, activeSession == null ? null : activeSession.getId());
        }, "list sessions");
    }

    @Override
    public void runSearch(String pattern) {
        if (activeSession == null) {
            ui.showSearchResults(pattern, List.of(),
                    "Create or open a session first \u2014 search runs in the session"
                            + " workspace");
            return;
        }
        String sessionId = activeSession.getId();
        Ui.run(api().executeTool("search_code", sessionId, Map.of("pattern", pattern)),
                json -> Ui.async(() -> renderSearchResults(pattern, json)),
                "workspace search");
    }

    private void renderSearchResults(String pattern, JsonNode json) {
        String output = json.path("output").asText("");
        String error = "";
        List<SearchResult> results = new ArrayList<>();
        try {
            JsonNode parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(output);
            if (!parsed.path("success").asBoolean(false)) {
                error = parsed.path("error").asText("search failed");
            } else {
                String text = parsed.path("output").asText("");
                for (String line : text.split("\n")) {
                    if (line.isBlank()) {
                        continue;
                    }
                    int first = line.indexOf(':');
                    int second = line.indexOf(':', first + 1);
                    if (first < 0 || second < 0) {
                        results.add(new SearchResult(line, 0, ""));
                        continue;
                    }
                    try {
                        results.add(new SearchResult(line.substring(0, first),
                                Integer.parseInt(line.substring(first + 1, second)),
                                line.substring(second + 1).strip()));
                    } catch (NumberFormatException e) {
                        results.add(new SearchResult(line, 0, ""));
                    }
                }
            }
        } catch (Exception e) {
            error = "Search failed: " + e.getMessage();
        }
        ui.showSearchResults(pattern, results, error);
    }

    // ------------------------------------------------------- SSE events

    @Override
    public void onEvent(DCodeSseClient.SseEvent event) {
        // The workbench SSE stream is already filtered server-side by the active
        // session id; this view follows the workbench active session.
        if (activeSession == null) {
            if (isSessionLifecycle(event.type())) {
                refreshSessions();
            }
            return;
        }
        switch (event.type()) {
            case "MESSAGE_PARTIAL" -> {
                String content = parseField(event.data(), "content");
                if (!content.isEmpty()) {
                    if (streamingEntryId == null) {
                        streamingEntryId = ui.addStreamingMessage(MessageKind.ASSISTANT,
                                "Assistant");
                    }
                    ui.appendToStreaming(streamingEntryId, content);
                }
            }
            case "MESSAGE_APPENDED" -> renderAllMessages();
            case "SESSION_STATE_CHANGED" -> onSessionStateChanged(event.data());
            case "AGENT_RUN_STARTED" -> ui.addStatusEntry("Agent run started", StatusKind.BUSY);
            case "AGENT_RUN_COMPLETED" -> ui.addStatusEntry("Agent run completed",
                    StatusKind.SUCCESS);
            case "TOOL_CALL_STARTED" -> ui.addStatusEntry("Tool call started: "
                    + parseField(event.data(), "toolName"), StatusKind.BUSY);
            case "TOOL_CALL_COMPLETED" -> ui.addStatusEntry("Tool call completed: "
                    + parseField(event.data(), "toolName"), StatusKind.SUCCESS);
            case "SYSTEM_ERROR" -> ui.addStatusEntry("Execution error: "
                    + parseField(event.data(), "message"), StatusKind.ERROR);
            default -> { /* other events refresh the session list below */ }
        }
        if (isSessionLifecycle(event.type())) {
            refreshSessions();
        }
    }

    private static boolean isSessionLifecycle(String type) {
        return type.equals("SESSION_CREATED") || type.equals("SESSION_RENAMED")
                || type.equals("SESSION_DELETED");
    }

    private void onSessionStateChanged(String data) {
        String to = parseField(data, "to");
        switch (to) {
            case "RUNNING" -> {
                busy = true;
                ui.setBusy(true);
                ui.setStatus("Running\u2026", StatusKind.BUSY);
            }
            case "COMPLETED" -> finish("Completed");
            case "WAITING_FOR_APPROVAL" -> finish(
                    "Completed \u2014 changes pending review (see Changes view)");
            case "CANCELLED" -> finish("Cancelled");
            case "FAILED" -> finish("Failed");
            default -> { /* intermediate states */ }
        }
        refreshSessions();
    }

    private void finish(String statusText) {
        busy = false;
        ui.setBusy(false);
        ui.setStatus(statusText, StatusKind.SUCCESS);
        renderAllMessages();
    }

    private void renderAllMessages() {
        if (activeSession == null) {
            return;
        }
        String sessionId = activeSession.getId();
        Ui.run(api().messages(sessionId), messages -> {
            for (JsonNode message : messages) {
                String id = message.path("id").asText("");
                if (id.isBlank() || !renderedMessageIds.add(id)) {
                    continue;
                }
                String role = message.path("role").asText("");
                String content = message.path("content").asText("");
                switch (role) {
                    case "USER" -> ui.addMessage(MessageKind.USER, "You", content);
                    case "ASSISTANT" -> {
                        if (streamingEntryId != null) {
                            ui.finishStreaming(streamingEntryId, content);
                            streamingEntryId = null;
                        } else {
                            ui.addMessage(MessageKind.ASSISTANT, "Assistant", content);
                        }
                    }
                    case "TOOL" -> ui.addMessage(MessageKind.TOOL, "Tool", content);
                    default -> ui.addStatusEntry(shorten(content, 120), StatusKind.NONE);
                }
            }
        }, "load messages");
    }

    // ------------------------------------------------------- connection state

    @Override
    public void stateChanged(ConnectionState state, BackendIdentity identity,
                             String diagnostic) {
        if (state == ConnectionState.READY) {
            Ui.async(this::refreshConfiguration);
        }
    }

    // ------------------------------------------------------- helpers

    private DCodeApiClient api() {
        return RuntimeConnection.instance().api();
    }

    private AgentRole parseAgentRole() {
        if (agentRoleName == null || agentRoleName.isBlank()) {
            return AgentRole.MAIN;
        }
        try {
            return AgentRole.valueOf(agentRoleName);
        } catch (IllegalArgumentException e) {
            return AgentRole.MAIN;
        }
    }

    private static String parseField(String dataJson, String field) {
        try {
            JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(dataJson);
            return node.path(field).asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        String singleLine = text.replace('\n', ' ');
        return singleLine.length() <= max ? singleLine
                : singleLine.substring(0, max) + "\u2026";
    }

    private static String nvl(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public void dispose() {
        disposed = true;
        Ui.removeSseEventListener(this);
        Ui.removeSessionListener(this);
        RuntimeConnection.instance().removeStateListener(this);
        if (composite != null) {
            composite.dispose();
            composite = null;
        }
        super.dispose();
    }

    @Override
    public void setFocus() {
        if (composite != null) {
            composite.setFocus();
        }
    }
}
