package eu.kalafatic.dcode.config.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.core.log.DCodeLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON-file runtime store ({@code <dataDir>/runtimes.json}). Writes are atomic
 * (temp file + move); secrets never appear in the file — they are stored by the
 * runtime manager in the credential vault.
 */
public final class JsonFileRuntimeStore implements RuntimeStore {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonFileRuntimeStore(Path dataDir) {
        this.file = dataDir.resolve("runtimes.json");
    }

    public Path file() {
        return file;
    }

    @Override
    public RuntimeStoreData load() {
        try {
            if (!Files.exists(file)) {
                return RuntimeStoreData.empty();
            }
            JsonNode root = mapper.readTree(Files.readString(file));
            String defaultRuntimeId = root.path("defaultRuntimeId").asText(null);
            String defaultModelId = root.path("defaultModelId").asText(null);
            List<RuntimeRegistration> registrations = new ArrayList<>();
            for (JsonNode node : root.path("runtimes")) {
                try {
                    registrations.add(parseRegistration(node));
                } catch (Exception e) {
                    DCodeLog.warn("config", "Skipping invalid runtime registration: "
                            + e.getMessage());
                }
            }
            return new RuntimeStoreData(
                    defaultRuntimeId != null && !defaultRuntimeId.isBlank()
                            ? defaultRuntimeId : null,
                    defaultModelId != null && !defaultModelId.isBlank()
                            ? defaultModelId : null,
                    registrations);
        } catch (Exception e) {
            DCodeLog.error("config", "Failed to load runtime registrations", e);
            return RuntimeStoreData.empty();
        }
    }

    @Override
    public void save(RuntimeStoreData data) {
        try {
            ObjectNode root = mapper.createObjectNode();
            if (data.defaultRuntimeId() != null) {
                root.put("defaultRuntimeId", data.defaultRuntimeId());
            }
            if (data.defaultModelId() != null) {
                root.put("defaultModelId", data.defaultModelId());
            }
            ArrayNode array = root.putArray("runtimes");
            for (RuntimeRegistration registration : data.registrations()) {
                array.add(writeRegistration(registration));
            }
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            DCodeLog.error("config", "Failed to persist runtime registrations", e);
        }
    }

    private RuntimeRegistration parseRegistration(JsonNode node) {
        String id = node.path("id").asText("");
        if (id.isBlank()) {
            throw new IllegalArgumentException("registration without id");
        }
        RuntimeType type = RuntimeType.fromWire(node.path("type").asText(""));
        String name = node.path("name").asText(id);
        String endpoint = node.path("endpoint").asText("");
        boolean enabled = node.path("enabled").asBoolean(true);
        boolean defaultRuntime = node.path("default").asBoolean(false);
        Map<String, String> configuration = new LinkedHashMap<>();
        JsonNode config = node.path("configuration");
        if (config.isObject()) {
            config.fields().forEachRemaining(entry ->
                    configuration.put(entry.getKey(), entry.getValue().asText("")));
        }
        return new RuntimeRegistration(id, name, type, endpoint, enabled,
                defaultRuntime, configuration);
    }

    private ObjectNode writeRegistration(RuntimeRegistration registration) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", registration.id());
        node.put("name", registration.name());
        node.put("type", registration.type().wireName());
        node.put("endpoint", registration.endpoint());
        node.put("enabled", registration.enabled());
        node.put("default", registration.defaultRuntime());
        ObjectNode config = node.putObject("configuration");
        for (Map.Entry<String, String> entry : registration.configuration().entrySet()) {
            config.put(entry.getKey(), entry.getValue());
        }
        return node;
    }
}
