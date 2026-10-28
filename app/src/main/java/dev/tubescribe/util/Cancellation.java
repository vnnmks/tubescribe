package dev.tubescribe.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cooperative cancellation for a pipeline run.
 *
 * <p>Long-running blocking operations (OkHttp calls, ffmpeg processes) register a
 * hook so that cancelling the run interrupts them promptly.</p>
 */
public final class Cancellation {
    private static final Logger LOG = Logger.getLogger(Cancellation.class.getName());

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Runnable> hooks = new CopyOnWriteArrayList<>();

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new OperationCancelledException();
        }
    }

    /**
     * Registers a hook that is run when cancellation is requested. Returns an
     * action that removes the hook again (safe to call more than once).
     */
    public Runnable addHook(final Runnable hook) {
        if (cancelled.get()) {
            hook.run();
            return () -> {
            };
        }
        hooks.add(hook);
        if (cancelled.get() && hooks.remove(hook)) {
            hook.run();
        }
        return () -> hooks.remove(hook);
    }

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        for (final Runnable hook : hooks) {
            try {
                hook.run();
            } catch (final RuntimeException e) {
                LOG.log(Level.WARNING, "Cancellation hook failed", e);
            }
        }
        hooks.clear();
    }
}
