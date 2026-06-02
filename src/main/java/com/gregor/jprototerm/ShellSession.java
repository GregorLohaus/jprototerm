package com.gregor.jprototerm;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ShellSession implements AutoCloseable {
    private final LinuxPty pty;
    private final ExecutorService reader;
    private volatile boolean closed;

    private ShellSession(LinuxPty pty) {
        this.pty = pty;
        this.reader = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shell-output-reader");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts the configured shell. {@code shellCommand} is the executable plus its arguments (e.g.
     * {@code ["/bin/bash", "-i"]}), spawned verbatim — any interactive flag is the user's choice in
     * config, not assumed here.
     */
    public static ShellSession start(List<String> shellCommand, Map<String, String> envOverride, TerminalPane pane,
            int columns, int rows, String workingDirectory, int closeSignal) {
        try {
            return spawn(shellCommand.toArray(new String[0]), envOverride, columns, rows, workingDirectory,
                    closeSignal);
        } catch (RuntimeException ex) {
            pane.write("failed to start shell: " + ex.getMessage() + "\r\n");
            throw new IllegalStateException("Could not start shell " + String.join(" ", shellCommand), ex);
        }
    }

    /**
     * Starts a session whose first and only process is {@code /bin/sh -c command}, so the program
     * runs deterministically from the start rather than being typed into an interactive shell —
     * there is no startup/rc race to lose or mangle the input. When the process exits the pty
     * closes and the pane auto-closes. {@code /bin/sh -c} is used (not the user's configured shell)
     * because it is the portable way to run a command line and does not depend on shell-specific
     * flags. {@code command} must not be null.
     */
    public static ShellSession startCommand(Map<String, String> envOverride, TerminalPane pane,
            int columns, int rows, String workingDirectory, String command, int closeSignal) {
        try {
            return spawn(new String[] {"/bin/sh", "-c", command}, envOverride, columns, rows, workingDirectory,
                    closeSignal);
        } catch (RuntimeException ex) {
            pane.write("failed to run command: " + ex.getMessage() + "\r\n");
            throw new IllegalStateException("Could not run command: " + command, ex);
        }
    }

    private static ShellSession spawn(String[] argv, Map<String, String> envOverride,
            int columns, int rows, String workingDirectory, int closeSignal) {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("TERM", "xterm-kitty");
        environment.put("COLORTERM", "truecolor");
        sanitizeWrapperEnvironment(environment);
        environment.putAll(envOverride);

        LinuxPty pty = LinuxPty.spawn(
                argv,
                environment,
                workingDirectory != null ? workingDirectory : System.getProperty("user.home"),
                closeSignal);
        ShellSession session = new ShellSession(pty);
        session.resize(columns, rows);
        return session;
    }

    /**
     * Strips the variables injected by the Nix launcher wrapper from the shell's
     * environment so they do not leak into terminal subprocesses.
     *
     * <p>jprototerm is launched from a Nix wrapper that prepends Nix store paths to
     * {@code LD_LIBRARY_PATH} (and adds a GL shim) so the bundled JavaFX/ghostty natives
     * resolve. If the shell inherited that path, host programs run inside the terminal
     * (e.g. {@code flatpak}, {@code pdftoppm}) would load the Nix copies of libraries such
     * as freetype/fontconfig/glib, which in turn drag in the Nix glibc through their
     * RUNPATHs and clash with the host {@code libc.so.6}. We restore the user's original
     * {@code LD_LIBRARY_PATH}, captured by the wrapper before it prepended anything.
     */
    private static void sanitizeWrapperEnvironment(Map<String, String> environment) {
        String hostLibraryPath = environment.remove("JPROTOTERM_HOST_LD_LIBRARY_PATH");
        if (hostLibraryPath == null || hostLibraryPath.isEmpty()) {
            environment.remove("LD_LIBRARY_PATH");
        } else {
            environment.put("LD_LIBRARY_PATH", hostLibraryPath);
        }
        // These are jprototerm's own runtime settings, not the user's shell environment.
        environment.remove("GDK_BACKEND");
        environment.remove("JLIBGHOSTTY_LIBRARY");
        sanitizeJavaToolOptions(environment);
    }

    private static void sanitizeJavaToolOptions(Map<String, String> environment) {
        String javaToolOptions = environment.get("JAVA_TOOL_OPTIONS");
        if (javaToolOptions == null
                || !javaToolOptions.contains("-XX:SharedArchiveFile=")
                || !javaToolOptions.contains("/jprototerm/app")) {
            return;
        }

        String sanitized = javaToolOptions
                .replaceAll("(^|\\s)-XX:\\+AutoCreateSharedArchive(?=\\s|$)", " ")
                .replaceAll("(^|\\s)-XX:SharedArchiveFile=\\S*/jprototerm/app\\S*(?=\\s|$)", " ")
                .trim();
        if (sanitized.isEmpty()) {
            environment.remove("JAVA_TOOL_OPTIONS");
        } else {
            environment.put("JAVA_TOOL_OPTIONS", sanitized);
        }
    }

    public void startReading(TerminalPane pane) {
        reader.submit(() -> readOutput(pane));
    }

    /** Best-effort current working directory of the running shell, or {@code null} if unknown. */
    public String currentWorkingDirectory() {
        return closed ? null : pty.currentWorkingDirectory();
    }

    public void resize(int columns, int rows) {
        if (closed) {
            return;
        }
        pty.setWinSize(columns, rows);
    }

    public void send(String text) {
        send(text.getBytes(StandardCharsets.UTF_8));
    }

    public void send(byte[] bytes) {
        if (closed) {
            return;
        }
        try {
            pty.write(bytes);
        } catch (RuntimeException ex) {
            close();
        }
    }

    private void readOutput(TerminalPane pane) {
        byte[] buffer = new byte[65536];
        try {
            int read;
            while ((read = pty.read(buffer)) != -1) {
                if (closed) {
                    break;
                }
                byte[] bytes = new byte[read];
                System.arraycopy(buffer, 0, bytes, 0, read);
                // Feed the terminal model straight from the reader thread. terminal access is
                // guarded by the per-terminal lock, and the render loop picks the change up on
                // the next pulse. Avoiding a Platform.runLater hop per chunk removes a frame of
                // latency and stops write tasks from contending with rendering on the FX thread
                // when a TUI repaints heavily (the input-lag culprit).
                pane.write(bytes);
            }
        } catch (RuntimeException ex) {
            if (!closed) {
                pane.write("\r\nshell output stopped: " + ex.getMessage() + "\r\n");
            }
        }
        // The stream ended without us closing the session, so the process exited on its own (the
        // user typed `exit`, or a one-shot command pane finished). Let the pane tear itself down.
        if (!closed) {
            pane.handleSessionExit();
        }
    }

    @Override
    public void close() {
        closed = true;
        reader.shutdownNow();
        pty.close();
    }
}
