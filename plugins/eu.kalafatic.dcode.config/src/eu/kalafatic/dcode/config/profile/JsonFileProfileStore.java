package eu.kalafatic.dcode.config.profile;

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
 * JSON-file profile store ({@code <dataDir>/profiles.json}): custom conversation
 * profiles plus the default-profile selection. Built-in profiles are not persisted.
 * Writes are atomic.
 */
public final class JsonFileProfileStore {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonFileProfileStore(Path dataDir) {
        this.file = dataDir.resolve("profiles.json");
    }

    public record ProfileStoreData(String defaultProfileId,
            List<ConversationProfile> customProfiles) {
        public ProfileStoreData {
            customProfiles = customProfiles == null ? List.of() : List.copyOf(customProfiles);
        }

        public static ProfileStoreData empty() {
            return new ProfileStoreData(null, List.of());
        }
    }

    public ProfileStoreData load() {
        try {
            if (!Files.exists(file)) {
                return ProfileStoreData.empty();
            }
            JsonNode root = mapper.readTree(Files.readString(file));
            String defaultProfileId = root.path("defaultProfileId").asText(null);
            List<ConversationProfile> profiles = new ArrayList<>();
            for (JsonNode node : root.path("profiles")) {
                profiles.add(new ConversationProfile(
                        node.path("id").asText(""),
                        node.path("name").asText(""),
                        node.path("description").asText(""),
                        node.path("systemInstructions").asText(""),
                        false));
            }
            return new ProfileStoreData(
                    defaultProfileId != null && !defaultProfileId.isBlank()
                            ? defaultProfileId : null, profiles);
        } catch (Exception e) {
            DCodeLog.error("config", "Failed to load conversation profiles", e);
            return ProfileStoreData.empty();
        }
    }

    public void save(ProfileStoreData data) {
        try {
            ObjectNode root = mapper.createObjectNode();
            if (data.defaultProfileId() != null) {
                root.put("defaultProfileId", data.defaultProfileId());
            }
            ArrayNode array = root.putArray("profiles");
            for (ConversationProfile profile : data.customProfiles()) {
                ObjectNode node = array.addObject();
                node.put("id", profile.id());
                node.put("name", profile.name());
                node.put("description", profile.description());
                node.put("systemInstructions", profile.systemInstructions());
            }
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            DCodeLog.error("config", "Failed to persist conversation profiles", e);
        }
    }
}
