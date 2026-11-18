package eu.kalafatic.dcode.core.log;

import java.util.regex.Pattern;

/**
 * Redacts secrets from log lines and URIs.
 *
 * <p>Applied on every log statement by {@link DCodeLog}. This is a best-effort redaction
 * layer; secrets must additionally never be logged at the call site and never be returned
 * unmasked by services.</p>
 */
public final class LogSanitizer {

    private static final Pattern AUTH_HEADER = Pattern.compile(
            "(?i)\\b(authorization\\s*[=:]\\s*(?:bearer\\s+)?)[^\\s,;]+");

    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\b(bearer\\s+)([^\\s,;]+)");

    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)\\b(api[_-]?key|apikey|access[_-]?token|secret|password|passwd|token|credential)\\b"
                    + "(\\s*[=:]\\s*)([^\\s,;\"']+)");

    private static final Pattern URI_USERINFO = Pattern.compile(
            "(?i)\\b(https?://)([^/@\\s:]+):([^@\\s]+)@");

    private LogSanitizer() {
    }

    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = AUTH_HEADER.matcher(text).replaceAll("$1***");
        result = BEARER.matcher(result).replaceAll("$1***");
        result = KEY_VALUE_SECRET.matcher(result).replaceAll("$1$2***");
        result = URI_USERINFO.matcher(result).replaceAll("$1$2:***@");
        return result;
    }

    /** Redacts the credentials portion of a URI string. */
    public static String sanitizeUri(String uri) {
        return sanitize(uri);
    }
}
