package eu.kalafatic.dcode.harness.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.core.log.DCodeLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON-file harness settings store ({@code <dataDir>/harnesses.json}): the selected
 * default harness plus per-harness enablement overrides. Writes are atomic.
 */
public final class JsonFileHarnessStore {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonFileHarnessStore(Path dataDir) {
        this.file = dataDir.resolve("harnesses.json");
    }

    public record HarnessStoreData(String defaultHarnessId,
            Map<String, Boolean> enabled) {
        public HarnessStoreData {
            enabled = enabled == null ? Map.of() : Map.copyOf(enabled);
        }

        public static HarnessStoreData empty() {
            return new HarnessStoreData(null, Map.of());
        }
    }

    public HarnessStoreData load() {
        try {
            if (!Files.exists(file)) {
                return HarnessStoreData.empty();
            }
            JsonNode root = mapper.readTree(Files.readString(file));
            String defaultHarnessId = root.path("defaultHarnessId").asText(null);
            Map<String, Boolean> enabled = new LinkedHashMap<>();
            root.path("enabled").fields().forEachRemaining(entry ->
                    enabled.put(entry.getKey(), entry.getValue().asBoolean(true)));
            return new HarnessStoreData(
                    defaultHarnessId != null && !defaultHarnessId.isBlank()
                            ? defaultHarnessId : null, enabled);
        } catch (Exception e) {
            DCodeLog.error("harness", "Failed to load harness settings", e);
            return HarnessStoreData.empty();
        }
    }

    public void save(HarnessStoreData data) {
        try {
            ObjectNode root = mapper.createObjectNode();
            if (data.defaultHarnessId() != null) {
                root.put("defaultHarnessId", data.defaultHarnessId());
            }
            ObjectNode enabled = root.putObject("enabled");
            data.enabled().forEach(enabled::put);
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            DCodeLog.error("harness", "Failed to persist harness settings", e);
        }
    }
}
