package com.gregor.jprototerm;

import javafx.application.Platform;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ShellSession implements AutoCloseable {
    private final Process process;
    private final OutputStream stdin;
    private final ExecutorService reader;
    private volatile boolean closed;

    private ShellSession(Process process, TerminalPane pane, KittyGraphicsRegistry graphicsRegistry) {
        this.process = process;
        this.stdin = process.getOutputStream();
        this.reader = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shell-output-reader");
            thread.setDaemon(true);
            return thread;
        });
        reader.submit(() -> readOutput(pane, graphicsRegistry));
    }

    public static ShellSession start(String shell, TerminalPane pane, KittyGraphicsRegistry graphicsRegistry) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "script",
                    "-qfec",
                    shell + " -i",
                    "/dev/null"
            ).redirectErrorStream(true);
            processBuilder.environment().put("TERM", "xterm-kitty");
            processBuilder.environment().put("COLORTERM", "truecolor");
            Process process = processBuilder.start();
            return new ShellSession(process, pane, graphicsRegistry);
        } catch (IOException ex) {
            pane.write("failed to start shell: " + ex.getMessage() + "\r\n");
            throw new IllegalStateException("Could not start shell " + shell, ex);
        }
    }

    public void send(String text) {
        if (closed) {
            return;
        }
        try {
            stdin.write(text.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException ex) {
            close();
        }
    }

    private void readOutput(TerminalPane pane, KittyGraphicsRegistry graphicsRegistry) {
        byte[] buffer = new byte[8192];
        try {
            int read;
            while ((read = process.getInputStream().read(buffer)) != -1) {
                String text = new String(buffer, 0, read, StandardCharsets.UTF_8);
                if (!closed) {
                    graphicsRegistry.accept(text);
                    Platform.runLater(() -> {
                        if (!closed) {
                            pane.write(text);
                        }
                    });
                }
            }
        } catch (IOException ex) {
            if (!closed) {
                Platform.runLater(() -> pane.write("\r\nshell output stopped: " + ex.getMessage() + "\r\n"));
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        reader.shutdownNow();
        process.destroy();
    }
}
