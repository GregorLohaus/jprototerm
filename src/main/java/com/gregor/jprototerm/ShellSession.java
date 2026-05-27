package com.gregor.jprototerm;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import javafx.application.Platform;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ShellSession implements AutoCloseable {
    private final PtyProcess process;
    private final OutputStream stdin;
    private final ExecutorService reader;
    private volatile boolean closed;

    private ShellSession(PtyProcess process, TerminalPane pane) {
        this.process = process;
        this.stdin = process.getOutputStream();
        this.reader = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shell-output-reader");
            thread.setDaemon(true);
            return thread;
        });
        reader.submit(() -> readOutput(pane));
    }

    public static ShellSession start(String shell, TerminalPane pane, int columns, int rows) {
        try {
            Map<String, String> environment = new HashMap<>(System.getenv());
            environment.put("TERM", "xterm-kitty");
            environment.put("COLORTERM", "truecolor");

            PtyProcess process = new PtyProcessBuilder(new String[] {shell, "-i"})
                    .setEnvironment(environment)
                    .setInitialColumns(columns)
                    .setInitialRows(rows)
                    .setDirectory(System.getProperty("user.home"))
                    .start();
            return new ShellSession(process, pane);
        } catch (IOException ex) {
            pane.write("failed to start shell: " + ex.getMessage() + "\r\n");
            throw new IllegalStateException("Could not start shell " + shell, ex);
        }
    }

    public void resize(int columns, int rows) {
        if (closed) {
            return;
        }
        process.setWinSize(new WinSize(columns, rows));
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

    private void readOutput(TerminalPane pane) {
        byte[] buffer = new byte[8192];
        try {
            int read;
            while ((read = process.getInputStream().read(buffer)) != -1) {
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
