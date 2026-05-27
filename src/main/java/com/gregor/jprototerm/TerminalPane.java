package com.gregor.jprototerm;

import dev.jlibghostty.Ghostty;
import dev.jlibghostty.Terminal;
import dev.jlibghostty.TerminalOptions;

import java.util.concurrent.atomic.AtomicReference;

public final class TerminalPane implements AutoCloseable {
    private final Terminal terminal;
    private final KittyGraphicsRegistry graphicsRegistry;
    private final AtomicReference<String> snapshotText = new AtomicReference<>("");
    private ShellSession session;
    private boolean floating;
    private double x;
    private double y;
    private double width;
    private double height;

    private TerminalPane(Terminal terminal, KittyGraphicsRegistry graphicsRegistry) {
        this.terminal = terminal;
        this.graphicsRegistry = graphicsRegistry;
    }

    public static TerminalPane create(int columns, int rows, boolean kittyGraphics) {
        Terminal terminal = Ghostty.open(TerminalOptions.of(columns, rows));
        TerminalPane pane = new TerminalPane(terminal, new KittyGraphicsRegistry(kittyGraphics));
        pane.refresh();
        return pane;
    }

    public void write(String text) {
        synchronized (terminal) {
            terminal.write(text);
            refresh();
        }
    }

    public void attach(ShellSession session) {
        this.session = session;
    }

    public void send(String text) {
        if (session != null) {
            session.send(text);
        }
    }

    public String snapshotText() {
        return snapshotText.get();
    }

    public KittyGraphicsRegistry graphicsRegistry() {
        return graphicsRegistry;
    }

    public boolean floating() {
        return floating;
    }

    public void setFloating(boolean floating) {
        this.floating = floating;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double width() {
        return width;
    }

    public double height() {
        return height;
    }

    public void bounds(double x, double y, double width, double height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    private void refresh() {
        snapshotText.set(String.valueOf(terminal.snapshot()));
    }

    @Override
    public void close() {
        if (session != null) {
            session.close();
            session = null;
        }
        terminal.close();
    }
}
