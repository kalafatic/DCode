package eu.kalafatic.dcode.config.runtime;

/**
 * Handle to an in-flight streaming generation. Cancellation is asynchronous and
 * idempotent; the terminal listener callback still arrives after cancellation.
 */
public interface StreamHandle {

    /** Requests cancellation of the generation. Idempotent. */
    void cancel();

    /** Handle for streams that have already terminated or need no cancellation. */
    static StreamHandle noop() {
        return NoopStreamHandle.INSTANCE;
    }

    final class NoopStreamHandle implements StreamHandle {
        private static final NoopStreamHandle INSTANCE = new NoopStreamHandle();

        private NoopStreamHandle() {
        }

        @Override
        public void cancel() {
        }
    }
}
