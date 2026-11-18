package eu.kalafatic.dcode.persistence.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dcode.core.config.ConfigurationService;
import eu.kalafatic.dcode.core.config.CoreConfiguration;
import eu.kalafatic.dcode.core.log.DCodeLog;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable configuration service: configuration in a JSON file, secrets encrypted at
 * rest with AES-GCM. The key lives in a separate key file with owner-only permissions;
 * secrets never appear in the configuration file or logs.
 */
public final class DurableConfigurationService implements ConfigurationService {

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final Path configFile;
    private final Path keyFile;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, String> secrets = new ConcurrentHashMap<>();

    private volatile CoreConfiguration configuration;

    public DurableConfigurationService(Path dataDir, CoreConfiguration initial) {
        this.configFile = dataDir.resolve("dcode-config.json");
        this.keyFile = dataDir.resolve("secret.key");
        this.configuration = loadOrInitialize(initial);
        loadSecrets();
    }

    @Override
    public CoreConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    public synchronized void update(CoreConfiguration config) {
        this.configuration = config;
        persistConfiguration(config);
    }

    @Override
    public Set<String> secretKeys() {
        return Set.copyOf(secrets.keySet());
    }

    @Override
    public synchronized void setSecret(String key, String value) {
        secrets.put(key, value);
        persistSecrets();
    }

    @Override
    public Optional<String> getSecret(String key) {
        return Optional.ofNullable(secrets.get(key));
    }

    @Override
    public synchronized void removeSecret(String key) {
        secrets.remove(key);
        persistSecrets();
    }

    private CoreConfiguration loadOrInitialize(CoreConfiguration initial) {
        try {
            if (Files.exists(configFile)) {
                JsonNode root = mapper.readTree(Files.readString(configFile));
                CoreConfiguration loaded = new CoreConfiguration(
                        Path.of(root.path("dataDir").asText(initial.dataDir().toString())),
                        Path.of(root.path("workspacesDir").asText(
                                initial.workspacesDir().toString())),
                        Path.of(root.path("artifactsDir").asText(
                                initial.artifactsDir().toString())),
                        Path.of(root.path("logsDir").asText(initial.logsDir().toString())),
                        root.path("httpPort").asInt(initial.httpPort()),
                        root.path("managementPort").asInt(initial.managementPort()),
                        root.path("bindAddress").asText(initial.bindAddress()),
                        root.path("nodeId").asText(initial.nodeId()),
                        root.path("appVersion").asText(initial.appVersion()));
                return loaded;
            }
            persistConfiguration(initial);
            return initial;
        } catch (Exception e) {
            DCodeLog.error("config", "Failed to load configuration; using defaults", e);
            return initial;
        }
    }

    private void persistConfiguration(CoreConfiguration configuration) {
        try {
            Files.createDirectories(configFile.getParent());
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("dataDir", configuration.dataDir().toString());
            map.put("workspacesDir", configuration.workspacesDir().toString());
            map.put("artifactsDir", configuration.artifactsDir().toString());
            map.put("logsDir", configuration.logsDir().toString());
            map.put("httpPort", configuration.httpPort());
            map.put("managementPort", configuration.managementPort());
            map.put("bindAddress", configuration.bindAddress());
            map.put("nodeId", configuration.nodeId());
            map.put("appVersion", configuration.appVersion());
            Files.writeString(configFile, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(map));
        } catch (Exception e) {
            DCodeLog.error("config", "Failed to persist configuration", e);
        }
    }

    private void loadSecrets() {
        Path secretsFile = configFile.getParent().resolve("secrets.enc");
        try {
            if (!Files.exists(secretsFile)) {
                return;
            }
            JsonNode root = mapper.readTree(Files.readString(secretsFile));
            root.fields().forEachRemaining(entry -> {
                try {
                    String plain = decrypt(entry.getValue().asText());
                    secrets.put(entry.getKey(), plain);
                } catch (Exception e) {
                    DCodeLog.error("config", "Failed to decrypt secret " + entry.getKey());
                }
            });
        } catch (Exception e) {
            DCodeLog.error("config", "Failed to load secrets file", e);
        }
    }

    private void persistSecrets() {
        Path secretsFile = configFile.getParent().resolve("secrets.enc");
        try {
            java.util.Map<String, String> encrypted = new java.util.LinkedHashMap<>();
            for (var entry : secrets.entrySet()) {
                encrypted.put(entry.getKey(), encrypt(entry.getValue()));
            }
            Files.writeString(secretsFile, mapper.writeValueAsString(encrypted));
        } catch (Exception e) {
            DCodeLog.error("config", "Failed to persist secrets", e);
        }
    }

    private SecretKey key() throws Exception {
        if (Files.exists(keyFile)) {
            byte[] bytes = Base64.getDecoder().decode(Files.readString(keyFile).strip());
            return new javax.crypto.spec.SecretKeySpec(bytes, "AES");
        }
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256, new SecureRandom());
        SecretKey secretKey = generator.generateKey();
        Files.createDirectories(keyFile.getParent());
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(secretKey.getEncoded()));
        try {
            Files.setPosixFilePermissions(keyFile, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem; best effort
        }
        return secretKey;
    }

    private String encrypt(String plain) throws Exception {
        byte[] iv = new byte[GCM_IV_BYTES];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] encrypted = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(iv) + ":"
                + Base64.getEncoder().encodeToString(encrypted);
    }

    private String decrypt(String value) throws Exception {
        String[] parts = value.split(":", 2);
        byte[] iv = Base64.getDecoder().decode(parts[0]);
        byte[] encrypted = Base64.getDecoder().decode(parts[1]);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(encrypted),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
