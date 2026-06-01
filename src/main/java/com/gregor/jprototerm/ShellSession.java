package com.gregor.jprototerm;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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

    public static ShellSession start(String shell, Map<String, String> envOverride, TerminalPane pane,
            int columns, int rows, String workingDirectory) {
        try {
            Map<String, String> environment = new HashMap<>(System.getenv());
            environment.put("TERM", "xterm-kitty");
            environment.put("COLORTERM", "truecolor");
            sanitizeWrapperEnvironment(environment);
            environment.putAll(envOverride);

            LinuxPty pty = LinuxPty.spawn(
                    new String[] {shell, "-i"},
                    environment,
                    workingDirectory != null ? workingDirectory : System.getProperty("user.home"));
            ShellSession session = new ShellSession(pty);
            session.resize(columns, rows);
            return session;
        } catch (RuntimeException ex) {
            pane.write("failed to start shell: " + ex.getMessage() + "\r\n");
            throw new IllegalStateException("Could not start shell " + shell, ex);
        }
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
    }

    @Override
    public void close() {
        closed = true;
        reader.shutdownNow();
        pty.close();
    }
}
