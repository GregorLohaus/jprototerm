package com.gregor.jprototerm;

import dev.jlibghostty.Ghostty;
import dev.jlibghostty.KittyGraphics;
import dev.jlibghostty.MouseAction;
import dev.jlibghostty.MouseEncoder;
import dev.jlibghostty.MouseEncoderSize;
import dev.jlibghostty.MouseInput;
import dev.jlibghostty.RenderStateSnapshot;
import dev.jlibghostty.ScrollViewport;
import dev.jlibghostty.Terminal;
import dev.jlibghostty.TerminalOptions;
import dev.jlibghostty.DeviceAttributes;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class TerminalPane implements AutoCloseable {
    private final Terminal terminal;
    private final MouseEncoder mouseEncoder = new MouseEncoder();
    private final AtomicReference<RenderStateSnapshot> renderSnapshot = new AtomicReference<>();
    private ShellSession session;
    private boolean floating;
    private boolean visible = true;
    private double x;
    private double y;
    private double width;
    private double height;
    private int columns;
    private int rows;
    private int pixelWidth;
    private int pixelHeight;
    private long renderVersion;

    private TerminalPane(Terminal terminal, int columns, int rows) {
        this.terminal = terminal;
        this.columns = columns;
        this.rows = rows;
    }

    public static TerminalPane create(int columns, int rows, long maxScrollback) {
        Terminal terminal = Ghostty.open(new TerminalOptions(columns, rows, maxScrollback));
        terminal.setDeviceAttributesProvider(DeviceAttributes::xtermCompatible);
        TerminalPane pane = new TerminalPane(terminal, columns, rows);
        pane.refresh();
        return pane;
    }

    public void write(String text) {
        synchronized (terminal) {
            terminal.write(text);
            refresh();
        }
    }

    public void write(byte[] bytes) {
        synchronized (terminal) {
            terminal.write(bytes);
            refresh();
        }
    }

    public void attach(ShellSession session) {
        this.session = session;
        terminal.setPtyWriter(bytes -> {
            ShellSession current = this.session;
            if (current != null) {
                current.send(bytes);
            }
        });
        session.startReading(this);
    }

    public void send(String text) {
        scrollViewportToBottom();
        if (session != null) {
            session.send(text);
        }
    }

    public boolean sendMouse(MouseInput input, MouseEncoderSize size, boolean anyButtonPressed) {
        synchronized (terminal) {
            mouseEncoder.syncFromTerminal(terminal);
            mouseEncoder.setSize(size);
            mouseEncoder.setAnyButtonPressed(anyButtonPressed);
            mouseEncoder.setTrackLastCell(input.action() == MouseAction.MOTION && input.button().isEmpty());

            byte[] encoded = mouseEncoder.encode(input);
            if (encoded.length == 0) {
                return false;
            }

            if (session != null) {
                session.send(encoded);
            }
            return true;
        }
    }

    public void scrollViewport(long rows) {
        synchronized (terminal) {
            terminal.scrollViewport(ScrollViewport.delta(rows));
            refresh();
        }
    }

    public void scrollViewportToBottom() {
        synchronized (terminal) {
            terminal.scrollViewport(ScrollViewport.bottom());
            refresh();
        }
    }

    public RenderStateSnapshot renderSnapshot() {
        return renderSnapshot.get();
    }

    public long renderVersion() {
        return renderVersion;
    }

    public Optional<KittyGraphics> kittyGraphics() {
        synchronized (terminal) {
            return terminal.kittyGraphics();
        }
    }

    public boolean floating() {
        return floating;
    }

    public void setFloating(boolean floating) {
        this.floating = floating;
    }

    public boolean visible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
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

    public void resize(int columns, int rows, int pixelWidth, int pixelHeight) {
        if (columns <= 0 || rows <= 0 || pixelWidth <= 0 || pixelHeight <= 0) {
            return;
        }
        if (this.columns == columns && this.rows == rows && this.pixelWidth == pixelWidth && this.pixelHeight == pixelHeight) {
            return;
        }

        synchronized (terminal) {
            terminal.resize(columns, rows, pixelWidth, pixelHeight);
            if (session != null) {
                session.resize(columns, rows);
            }
            this.columns = columns;
            this.rows = rows;
            this.pixelWidth = pixelWidth;
            this.pixelHeight = pixelHeight;
            refresh();
        }
    }

    private void refresh() {
        renderSnapshot.set(terminal.renderSnapshot());
        renderVersion++;
    }

    @Override
    public void close() {
        if (session != null) {
            session.close();
            session = null;
        }
        mouseEncoder.close();
        terminal.close();
    }
}
