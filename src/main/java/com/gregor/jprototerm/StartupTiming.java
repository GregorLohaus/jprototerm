package com.gregor.jprototerm;

import java.lang.management.ManagementFactory;

/**
 * Opt-in startup phase timing, enabled with {@code -Djprototerm.timing=true} (e.g. via
 * {@code JAVA_TOOL_OPTIONS}); otherwise every method is a cheap no-op and prints nothing.
 *
 * <p>Each {@link #mark(String)} prints one line to stderr with the time since the previous mark and
 * the total since JVM start, so a cold launch breaks down into its phases — toolkit/GL init vs
 * config load vs font loading vs first frame. The anchor is the JVM's own start time (the closest
 * proxy we have to "process start"), so the first mark includes JVM bootstrap and JavaFX toolkit
 * init, which is usually the dominant cost.
 */
final class StartupTiming {
    private static final boolean ENABLED = Boolean.getBoolean("jprototerm.timing");
    // Epoch millis; getStartTime() is the JVM's start, the earliest timestamp we can anchor to.
    private static final long JVM_START_MILLIS = ManagementFactory.getRuntimeMXBean().getStartTime();
    private static long lastMillis = -1;
    private static boolean firstFrameSeen;

    private StartupTiming() {
    }

    /**
     * Records a phase boundary, printing the delta since the previous mark and since JVM start.
     * Synchronized because marks come from both the launcher thread and the FX thread.
     */
    static synchronized void mark(String phase) {
        if (!ENABLED) {
            return;
        }
        long now = System.currentTimeMillis();
        long sinceStart = now - JVM_START_MILLIS;
        long sinceLast = lastMillis < 0 ? sinceStart : now - lastMillis;
        lastMillis = now;
        System.err.printf("[timing] %-22s +%5d ms   (%5d ms since JVM start)%n", phase, sinceLast, sinceStart);
    }

    /**
     * Records the first rendered frame exactly once, then becomes a no-op. Safe and cheap to call
     * from the render loop every frame (it only ever touches FX-thread state).
     */
    static synchronized void firstFrame() {
        if (!ENABLED || firstFrameSeen) {
            return;
        }
        firstFrameSeen = true;
        mark("first frame");
    }
}
