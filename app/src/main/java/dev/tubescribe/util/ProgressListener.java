package dev.tubescribe.util;

/**
 * Receives coarse pipeline progress. A negative fraction means "indeterminate".
 * Implementations must be thread-safe: updates arrive from a worker thread.
 */
@FunctionalInterface
public interface ProgressListener {
    ProgressListener NOOP = (fraction, message) -> {
    };

    void update(double fraction, String message);

    default void log(final String message) {
        // Optional diagnostic channel.
    }
}
