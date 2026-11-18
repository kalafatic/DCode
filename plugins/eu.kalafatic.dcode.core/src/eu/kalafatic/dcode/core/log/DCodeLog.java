package eu.kalafatic.dcode.core.log;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal DCode logging facade.
 *
 * <p>Deliberately dependency-free: the core bundle must not depend on a logging framework.
 * All messages pass through {@link LogSanitizer} so secrets never reach the sink. The sink
 * is replaceable (server bundles may redirect to a file sink).</p>
 */
public final class DCodeLog {

    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }

    public interface Sink {
        void log(Level level, String source, String message, Throwable throwable);
    }

    private static final AtomicReference<Sink> SINK = new AtomicReference<>(defaultSink());
    private static final AtomicReference<Level> LEVEL = new AtomicReference<>(Level.INFO);

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private DCodeLog() {
    }

    public static void setSink(Sink sink) {
        SINK.set(sink == null ? defaultSink() : sink);
    }

    public static void setLevel(Level level) {
        LEVEL.set(level);
    }

    public static boolean isDebugEnabled() {
        return LEVEL.get().ordinal() <= Level.DEBUG.ordinal();
    }

    public static void debug(String source, String message) {
        log(Level.DEBUG, source, message, null);
    }

    public static void info(String source, String message) {
        log(Level.INFO, source, message, null);
    }

    public static void warn(String source, String message) {
        log(Level.WARN, source, message, null);
    }

    public static void error(String source, String message) {
        log(Level.ERROR, source, message, null);
    }

    public static void error(String source, String message, Throwable t) {
        log(Level.ERROR, source, message, t);
    }

    private static void log(Level level, String source, String message, Throwable t) {
        if (level.ordinal() < LEVEL.get().ordinal()) {
            return;
        }
        SINK.get().log(level, source, LogSanitizer.sanitize(message), t);
    }

    private static Sink defaultSink() {
        return (level, source, message, throwable) -> {
            String line = TIMESTAMP.format(Instant.now()) + " " + level + " [" + source + "] "
                    + message;
            if (level == Level.ERROR) {
                System.err.println(line);
            } else {
                System.out.println(line);
            }
            if (throwable != null) {
                throwable.printStackTrace(System.err);
            }
        };
    }
}
