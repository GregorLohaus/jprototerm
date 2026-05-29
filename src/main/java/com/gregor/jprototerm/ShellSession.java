package com.gregor.jprototerm;

import javafx.application.Platform;

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

    public static ShellSession start(String shell, Map<String, String> envOverride, TerminalPane pane, int columns, int rows) {
        try {
            Map<String, String> environment = new HashMap<>(System.getenv());
            environment.put("TERM", "xterm-kitty");
            environment.put("COLORTERM", "truecolor");
            environment.putAll(envOverride);

            LinuxPty pty = LinuxPty.spawn(
                    new String[] {shell, "-i"},
                    environment,
                    System.getProperty("user.home"));
            ShellSession session = new ShellSession(pty);
            session.resize(columns, rows);
            return session;
        } catch (RuntimeException ex) {
            pane.write("failed to start shell: " + ex.getMessage() + "\r\n");
            throw new IllegalStateException("Could not start shell " + shell, ex);
        }
    }

    public void startReading(TerminalPane pane) {
        reader.submit(() -> readOutput(pane));
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
        byte[] buffer = new byte[8192];
        try {
            int read;
            while ((read = pty.read(buffer)) != -1) {
                if (!closed) {
                    byte[] bytes = new byte[read];
                    System.arraycopy(buffer, 0, bytes, 0, read);
                    Platform.runLater(() -> {
                        if (!closed) {
                            pane.write(bytes);
                        }
                    });
                }
            }
        } catch (RuntimeException ex) {
            if (!closed) {
                Platform.runLater(() -> pane.write("\r\nshell output stopped: " + ex.getMessage() + "\r\n"));
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
