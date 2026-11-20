package eu.kalafatic.dcode.config.runtime;

import java.util.Locale;

/**
 * Well-known inference runtime types supported by DCode adapters.
 *
 * <p>One type may have many runtime instances (local Ollama, remote Ollama, development
 * ENR, production ENR, several OpenAI-compatible endpoints). The type only selects the
 * adapter; every registration carries its own identity and configuration.</p>
 */
public enum RuntimeType {

    ENR,
    OLLAMA,
    OPENAI_COMPATIBLE;

    /** Stable wire name used in persistence and the HTTP API. */
    public String wireName() {
        return switch (this) {
            case ENR -> "enr";
            case OLLAMA -> "ollama";
            case OPENAI_COMPATIBLE -> "openai";
        };
    }

    public String displayName() {
        return switch (this) {
            case ENR -> "ENR (EVO Native Runtime)";
            case OLLAMA -> "Ollama";
            case OPENAI_COMPATIBLE -> "OpenAI-compatible API";
        };
    }

    /** Parses a wire name; unknown values are rejected. */
    public static RuntimeType fromWire(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Runtime type is required");
        }
        String normalized = name.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "enr" -> ENR;
            case "ollama" -> OLLAMA;
            case "openai", "openai-compatible", "openai_compatible" -> OPENAI_COMPATIBLE;
            default -> throw new IllegalArgumentException("Unknown runtime type: " + name);
        };
    }
}
