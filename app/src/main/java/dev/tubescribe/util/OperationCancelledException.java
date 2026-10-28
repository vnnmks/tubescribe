package dev.tubescribe.util;

/** Thrown when a pipeline run is cancelled by the user. */
public final class OperationCancelledException extends RuntimeException {
    public OperationCancelledException() {
        super("Cancelled");
    }
}
