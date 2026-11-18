package eu.kalafatic.dcode.harness.tools;

import java.util.Map;

/** Common argument helpers for built-in tools. */
final class ToolArgs {

    private ToolArgs() {
    }

    static String string(Map<String, Object> arguments, String key, String defaultValue) {
        Object value = arguments.get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }

    static int integer(Map<String, Object> arguments, String key, int defaultValue) {
        Object value = arguments.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return defaultValue;
    }
}
