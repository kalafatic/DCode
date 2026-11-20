package eu.kalafatic.dcode.harness.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dcode.core.model.ChangeSet;
import eu.kalafatic.dcode.core.model.FileChange;
import eu.kalafatic.dcode.core.model.FileChangeStatus;
import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.repositories.service.RepositoryOperations;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Change management: collects per-repository change sets after agent execution.
 *
 * <p>Multi-repository work yields one change set per repository with changes; there is
 * no cross-repository transaction abstraction (family rule). Workspaces that are not
 * Git repositories (plain folders) fall back to the session's own tool-call record —
 * the files the engine actually wrote — so real file effects are never lost.</p>
 */
public final class ChangeManager {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> MUTATING_TOOLS = Set.of("write_file", "edit_file");

    private final RepositoryOperations repositoryOperations;
    private final eu.kalafatic.dcode.harness.tool.ToolEngine toolEngine;

    public ChangeManager(RepositoryOperations repositoryOperations) {
        this(repositoryOperations, null);
    }

    public ChangeManager(RepositoryOperations repositoryOperations,
                         eu.kalafatic.dcode.harness.tool.ToolEngine toolEngine) {
        this.repositoryOperations = repositoryOperations;
        this.toolEngine = toolEngine;
    }

    /** Collects change sets for all session repositories with working-tree changes. */
    public List<ChangeSet> collectChanges(Workspace workspace, String sessionId) {
        java.util.List<ChangeSet> result = new java.util.ArrayList<>();
        for (RepositoryRef repository : workspace.repositories()) {
            List<String> changed;
            try {
                changed = repositoryOperations.changedFiles(workspace, repository);
            } catch (RuntimeException gitError) {
                // Plain-folder workspaces (no .git) or broken repositories: use the
                // session's own tool-call record as the authoritative change list.
                changed = sessionToolFiles(sessionId);
            }
            if (changed.isEmpty()) {
                continue;
            }
            List<FileChange> files = new java.util.ArrayList<>();
            for (String path : changed) {
                files.add(new FileChange(path, null, FileChangeStatus.MODIFIED, List.of()));
            }
            result.add(new ChangeSet(UUID.randomUUID().toString(), sessionId,
                    repository.id(), "Changes in repository " + repository.name(), files,
                    java.time.Instant.now()));
        }
        return result;
    }

    /** Files written/modified by the session's own mutating tools (fallback source). */
    public static List<String> sessionToolFiles(
            eu.kalafatic.dcode.harness.tool.ToolEngine engine, String sessionId) {
        if (engine == null) {
            return List.of();
        }
        Set<String> files = new LinkedHashSet<>();
        for (eu.kalafatic.dcode.core.model.ToolCall call : engine.callsOf(sessionId)) {
            if (!MUTATING_TOOLS.contains(call.toolName())) {
                continue;
            }
            try {
                JsonNode arguments = MAPPER.readTree(call.argumentsJson());
                String path = arguments.path("path").asText("");
                if (!path.isBlank()) {
                    files.add(path);
                }
            } catch (Exception ignored) {
                // unparseable arguments: skip this call
            }
        }
        return List.copyOf(files);
    }

    private List<String> sessionToolFiles(String sessionId) {
        return sessionToolFiles(toolEngine, sessionId);
    }
}
