package com.gregor.jprototerm;

import javafx.application.Platform;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Owns the JavaFX toolkit lifecycle and the set of live {@link TerminalWindow}s for one JVM. A
 * single JVM hosts every window, so the expensive toolkit/GL init is paid once; opening another
 * window is then just a new {@link javafx.stage.Stage}.
 *
 * <p>Two modes differ only in the empty policy: in {@link Mode#STANDALONE} (today's behavior, used
 * as the fallback when no daemon is reachable) the JVM exits once the last window closes; in
 * {@link Mode#DAEMON} the toolkit stays alive with zero windows, waiting for the next client
 * request. {@link Platform#setImplicitExit(boolean) implicit exit} is disabled in both so the
 * toolkit never tears itself down behind our back — every exit is an explicit decision here.
 */
public final class WindowManager {
    public enum Mode {
        STANDALONE,
        DAEMON
    }

    private final Mode mode;
    // Mutated on the FX thread (register/deregister), iterated from the shutdown-hook thread.
    private final Set<TerminalWindow> windows = new CopyOnWriteArraySet<>();

    private WindowManager(Mode mode) {
        this.mode = mode;
    }

    /**
     * Brings up the JavaFX toolkit (once per JVM) and returns a manager in {@code mode}. Registers a
     * shutdown hook that reaps every window's shell processes, so child shells are terminated rather
     * than orphaned if the JVM is killed (SIGTERM/SIGINT/SIGHUP) — see
     * {@link Compositor#terminateSessions()}.
     */
    public static WindowManager start(Mode mode) {
        WindowManager manager = new WindowManager(mode);
        Platform.setImplicitExit(false);
        Platform.startup(() -> StartupTiming.mark("toolkit ready"));
        Runtime.getRuntime().addShutdownHook(new Thread(manager::terminateAllSessions, "shell-cleanup"));
        return manager;
    }

    /** Opens a new window (on the FX thread) whose first pane starts in {@code workingDirectory}. */
    public void openWindow(String workingDirectory) {
        Platform.runLater(() -> windows.add(new TerminalWindow(this, workingDirectory)));
    }

    /**
     * Called by a window when it has finished tearing down (FX thread). Drops it from the registry
     * and, in standalone mode, exits the JVM once none remain.
     */
    void onWindowClosed(TerminalWindow window) {
        windows.remove(window);
        if (mode == Mode.STANDALONE && windows.isEmpty()) {
            Platform.exit();
        }
    }

    /** Signals and reaps every live window's shell processes. Safe to call off the FX thread. */
    void terminateAllSessions() {
        for (TerminalWindow window : windows) {
            window.terminateSessions();
        }
    }
}
