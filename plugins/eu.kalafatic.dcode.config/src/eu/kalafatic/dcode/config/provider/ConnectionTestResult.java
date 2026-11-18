package eu.kalafatic.dcode.config.provider;

/** Result of a live provider connection test. */
public record ConnectionTestResult(
        boolean success,
        String message
) {
    public static ConnectionTestResult ok(String message) {
        return new ConnectionTestResult(true, message);
    }

    public static ConnectionTestResult error(String message) {
        return new ConnectionTestResult(false, message != null ? message : "Connection test failed");
    }
}
