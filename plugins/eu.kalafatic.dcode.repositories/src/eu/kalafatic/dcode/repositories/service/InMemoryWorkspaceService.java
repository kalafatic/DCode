package eu.kalafatic.dcode.repositories.service;

import eu.kalafatic.dcode.core.event.DCodeEventType;
import eu.kalafatic.dcode.core.event.EventBus;
import eu.kalafatic.dcode.core.event.StandardEvent;
import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.core.model.WorkspaceMode;
import eu.kalafatic.dcode.core.service.WorkspaceService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory workspace service (WP5.3). The SQLite-backed implementation replaces this
 * instance in WP5.7 while keeping the {@link WorkspaceService} contract unchanged.
 *
 * <p>Mutating registry operations publish events after the registry change is applied;
 * concurrent mutations are serialized per workspace.</p>
 */
public final class InMemoryWorkspaceService implements WorkspaceService {

    private final EventBus eventBus;
    private final ConcurrentHashMap<String, Workspace> workspaces = new ConcurrentHashMap<>();

    public InMemoryWorkspaceService(EventBus eventBus) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

    @Override
    public Workspace createWorkspace(String name, WorkspaceMode mode, String rootPath) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mode, "mode");
        Workspace workspace = new Workspace(UUID.randomUUID().toString(), name, mode, rootPath,
                List.of(), Instant.now());
        workspaces.put(workspace.id(), workspace);
        eventBus.publish(StandardEvent.system(DCodeEventType.WORKSPACE_CREATED,
                "{\"workspaceId\":\"" + workspace.id() + "\",\"name\":\"" + escape(name) + "\"}"));
        return workspace;
    }

    @Override
    public Optional<Workspace> getWorkspace(String workspaceId) {
        return Optional.ofNullable(workspaces.get(workspaceId));
    }

    @Override
    public List<Workspace> listWorkspaces() {
        return List.copyOf(workspaces.values());
    }

    @Override
    public Workspace addRepository(String workspaceId, RepositoryRef repository) {
        Objects.requireNonNull(repository, "repository");
        return workspaces.compute(workspaceId, (id, current) -> {
            if (current == null) {
                throw new IllegalArgumentException("Unknown workspace: " + workspaceId);
            }
            synchronized (current) {
                for (RepositoryRef existing : current.repositories()) {
                    if (existing.name().equals(repository.name())) {
                        throw new IllegalArgumentException("Workspace already contains a "
                                + "repository named '" + repository.name() + "'");
                    }
                }
                List<RepositoryRef> updated = new ArrayList<>(current.repositories());
                updated.add(repository);
                Workspace result = current.withRepositories(updated);
                eventBus.publish(StandardEvent.system(DCodeEventType.REPOSITORY_ADDED,
                        "{\"workspaceId\":\"" + workspaceId + "\",\"repositoryId\":\""
                                + repository.id() + "\"}"));
                return result;
            }
        });
    }

    @Override
    public Workspace removeRepository(String workspaceId, String repositoryId) {
        return workspaces.compute(workspaceId, (id, current) -> {
            if (current == null) {
                throw new IllegalArgumentException("Unknown workspace: " + workspaceId);
            }
            synchronized (current) {
                List<RepositoryRef> updated = new ArrayList<>();
                boolean found = false;
                for (RepositoryRef existing : current.repositories()) {
                    if (existing.id().equals(repositoryId)) {
                        found = true;
                    } else {
                        updated.add(existing);
                    }
                }
                if (!found) {
                    throw new IllegalArgumentException("No repository with id " + repositoryId
                            + " in workspace " + workspaceId);
                }
                Workspace result = current.withRepositories(updated);
                eventBus.publish(StandardEvent.system(DCodeEventType.REPOSITORY_REMOVED,
                        "{\"workspaceId\":\"" + workspaceId + "\",\"repositoryId\":\""
                                + repositoryId + "\"}"));
                return result;
            }
        });
    }

    @Override
    public List<RepositoryRef> listRepositories(String workspaceId) {
        return getWorkspace(workspaceId)
                .map(Workspace::repositories)
                .orElseThrow(() -> new IllegalArgumentException("Unknown workspace: " + workspaceId));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
