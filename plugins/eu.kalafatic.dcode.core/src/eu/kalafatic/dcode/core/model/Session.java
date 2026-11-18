package eu.kalafatic.dcode.core.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Central platform entity: one coding session.
 *
 * <p>Immutable value object. State changes produce a new instance via
 * {@link #withState(SessionState)}; persistence and lifecycle services own the authority to
 * perform and validate those changes. A session is presentation-client agnostic: it may be
 * observed and driven from RCP, Web or the HTTP API concurrently; mutation is serialized by
 * the owning service.</p>
 */
public final class Session {

    private final String id;
    private final String title;
    private final String workspaceId;
    private final SessionState state;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final String providerId;
    private final String modelId;
    private final AgentRole agentRole;
    private final Map<String, String> metadata;

    private Session(String id, String title, String workspaceId, SessionState state,
                    Instant createdAt, Instant updatedAt, String providerId, String modelId,
                    AgentRole agentRole, Map<String, String> metadata) {
        this.id = id;
        this.title = title;
        this.workspaceId = workspaceId;
        this.state = state;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.providerId = providerId;
        this.modelId = modelId;
        this.agentRole = agentRole;
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public SessionState getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getModelId() {
        return modelId;
    }

    public AgentRole getAgentRole() {
        return agentRole;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    /**
     * Returns a copy of this session in the given state with an updated timestamp.
     * Callers must validate the transition with
     * {@link SessionState#isValidTransition(SessionState, SessionState)} before calling.
     */
    public Session withState(SessionState newState) {
        return new Session(id, title, workspaceId, newState, createdAt, Instant.now(),
                providerId, modelId, agentRole, metadata);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Session other)) {
            return false;
        }
        return id.equals(other.id) && title.equals(other.title)
                && workspaceId.equals(other.workspaceId) && state == other.state
                && createdAt.equals(other.createdAt) && updatedAt.equals(other.updatedAt)
                && Objects.equals(providerId, other.providerId)
                && Objects.equals(modelId, other.modelId)
                && agentRole == other.agentRole && metadata.equals(other.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, title, workspaceId, state, createdAt, updatedAt,
                providerId, modelId, agentRole, metadata);
    }

    @Override
    public String toString() {
        return "Session{id='" + id + "', title='" + title + "', state=" + state + '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id = UUID.randomUUID().toString();
        private String title = "";
        private String workspaceId;
        private SessionState state = SessionState.CREATED;
        private Instant createdAt = Instant.now();
        private Instant updatedAt = createdAt;
        private String providerId;
        private String modelId;
        private AgentRole agentRole = AgentRole.MAIN;
        private Map<String, String> metadata = Map.of();

        public Builder id(String value) {
            this.id = Objects.requireNonNull(value, "id");
            return this;
        }

        public Builder title(String value) {
            this.title = Objects.requireNonNull(value, "title");
            return this;
        }

        public Builder workspaceId(String value) {
            this.workspaceId = value;
            return this;
        }

        public Builder state(SessionState value) {
            this.state = Objects.requireNonNull(value, "state");
            this.updatedAt = Instant.now();
            return this;
        }

        public Builder createdAt(Instant value) {
            this.createdAt = Objects.requireNonNull(value, "createdAt");
            if (this.updatedAt.equals(updatedAt)) {
                this.updatedAt = value;
            }
            return this;
        }

        public Builder updatedAt(Instant value) {
            this.updatedAt = Objects.requireNonNull(value, "updatedAt");
            return this;
        }

        public Builder providerId(String value) {
            this.providerId = value;
            return this;
        }

        public Builder modelId(String value) {
            this.modelId = value;
            return this;
        }

        public Builder agentRole(AgentRole value) {
            this.agentRole = Objects.requireNonNull(value, "agentRole");
            return this;
        }

        public Builder metadata(Map<String, String> value) {
            this.metadata = Objects.requireNonNull(value, "metadata");
            return this;
        }

        public Session build() {
            Objects.requireNonNull(workspaceId, "workspaceId is required");
            return new Session(id, title, workspaceId, state, createdAt, updatedAt,
                    providerId, modelId, agentRole, metadata);
        }
    }
}
