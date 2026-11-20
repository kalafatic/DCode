package eu.kalafatic.dcode.config.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.core.log.DCodeLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON-file agent store ({@code <dataDir>/agents.json}): custom agents plus the
 * default-agent selection. Built-in agents are not persisted. Writes are atomic.
 */
public final class JsonFileAgentStore {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonFileAgentStore(Path dataDir) {
        this.file = dataDir.resolve("agents.json");
    }

    public record AgentStoreData(String defaultAgentId, List<AgentDefinition> customAgents) {
        public AgentStoreData {
            customAgents = customAgents == null ? List.of() : List.copyOf(customAgents);
        }

        public static AgentStoreData empty() {
            return new AgentStoreData(null, List.of());
        }
    }

    public AgentStoreData load() {
        try {
            if (!Files.exists(file)) {
                return AgentStoreData.empty();
            }
            JsonNode root = mapper.readTree(Files.readString(file));
            String defaultAgentId = root.path("defaultAgentId").asText(null);
            List<AgentDefinition> agents = new ArrayList<>();
            for (JsonNode node : root.path("agents")) {
                agents.add(new AgentDefinition(
                        node.path("id").asText(""),
                        node.path("name").asText(""),
                        node.path("description").asText(""),
                        node.path("role").asText("MAIN"),
                        node.path("systemInstructions").asText(""),
                        stringList(node.path("toolPermissions")),
                        stringList(node.path("restrictions")),
                        node.path("preferredRuntimeId").asText(""),
                        node.path("preferredModel").asText(""),
                        false));
            }
            return new AgentStoreData(
                    defaultAgentId != null && !defaultAgentId.isBlank()
                            ? defaultAgentId : null, agents);
        } catch (Exception e) {
            DCodeLog.error("config", "Failed to load agent profiles", e);
            return AgentStoreData.empty();
        }
    }

    public void save(AgentStoreData data) {
        try {
            ObjectNode root = mapper.createObjectNode();
            if (data.defaultAgentId() != null) {
                root.put("defaultAgentId", data.defaultAgentId());
            }
            ArrayNode array = root.putArray("agents");
            for (AgentDefinition agent : data.customAgents()) {
                ObjectNode node = array.addObject();
                node.put("id", agent.id());
                node.put("name", agent.name());
                node.put("description", agent.description());
                node.put("role", agent.role());
                node.put("systemInstructions", agent.systemInstructions());
                node.put("preferredRuntimeId", agent.preferredRuntimeId());
                node.put("preferredModel", agent.preferredModel());
                ArrayNode permissions = node.putArray("toolPermissions");
                agent.toolPermissions().forEach(permissions::add);
                ArrayNode restrictions = node.putArray("restrictions");
                agent.restrictions().forEach(restrictions::add);
            }
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            DCodeLog.error("config", "Failed to persist agent profiles", e);
        }
    }

    private static List<String> stringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(entry -> result.add(entry.asText("")));
        }
        return result;
    }
}
