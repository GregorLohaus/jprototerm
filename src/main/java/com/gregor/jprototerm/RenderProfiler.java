package com.gregor.jprototerm;

/**
 * Lightweight render profiler, disabled unless {@code -Djprototerm.profile=true} (or the
 * {@code JPROTOTERM_PROFILE=1} environment variable) is set. It accumulates wall-clock nanos
 * into a handful of buckets and prints aggregate per-frame stats to stderr every
 * {@code jprototerm.profile.frames} render invocations (default 120).
 *
 * <p>All render work runs on the JavaFX application thread, so the accumulators are plain
 * fields with no synchronization.
 *
 * <p>Caveat: JavaFX canvas drawing is deferred to the QuantumRenderer thread, so the
 * {@link #DRAW} bucket measures only the cost of <em>recording</em> draw commands, not the
 * GPU paint. Pair this with {@code -Djavafx.pulseLogger=true} to see the render-thread side.
 */
final class RenderProfiler {
    static final int SNAPSHOT = 0;
    static final int FINGERPRINT = 1;
    static final int DRAW = 2;
    static final int FRAME = 3;
    static final int UPDATE = 4;
    static final int MARSHAL = 5;
    private static final int BUCKETS = 6;
    private static final String[] NAMES =
            {"snapshot", "fingerprint", "draw", "frame-total", "update", "marshal"};

    private static final boolean ENABLED =
            Boolean.getBoolean("jprototerm.profile") || "1".equals(System.getenv("JPROTOTERM_PROFILE"));
    private static final int DUMP_FRAMES = Integer.getInteger("jprototerm.profile.frames", 120);

    private static final long[] totalNanos = new long[BUCKETS];
    private static final long[] counts = new long[BUCKETS];
    private static int frames;

    private RenderProfiler() {
    }

    static boolean enabled() {
        return ENABLED;
    }

    /** Returns a start timestamp, or 0 when profiling is disabled. */
    static long start() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    /** Records the time elapsed since {@code startNanos} into {@code bucket}. */
    static void stop(int bucket, long startNanos) {
        if (!ENABLED) {
            return;
        }
        totalNanos[bucket] += System.nanoTime() - startNanos;
        counts[bucket]++;
    }

    /** Marks the end of one render invocation; dumps and resets every {@code DUMP_FRAMES}. */
    static void frame() {
        if (!ENABLED) {
            return;
        }
        if (++frames < DUMP_FRAMES) {
            return;
        }
        dump();
    }

    private static void dump() {
        StringBuilder sb = new StringBuilder(192);
        sb.append("[render-profile] ").append(frames).append(" renders");
        for (int i = 0; i < BUCKETS; i++) {
            double totalMs = totalNanos[i] / 1_000_000.0;
            sb.append(String.format(" | %s %.3fms/f (n=%d)", NAMES[i], totalMs / frames, counts[i]));
            totalNanos[i] = 0;
            counts[i] = 0;
        }
        System.err.println(sb);
        frames = 0;
    }
}
