package com.gregor.jprototerm;

import dev.jlibghostty.Ghostty;
import dev.jlibghostty.KittyGraphics;
import dev.jlibghostty.MouseAction;
import dev.jlibghostty.MouseEncoder;
import dev.jlibghostty.MouseEncoderSize;
import dev.jlibghostty.MouseInput;
import dev.jlibghostty.RenderState;
import dev.jlibghostty.RenderStateSnapshot;
import dev.jlibghostty.ScrollViewport;
import dev.jlibghostty.Terminal;
import dev.jlibghostty.TerminalOptions;
import dev.jlibghostty.DeviceAttributes;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class TerminalPane implements AutoCloseable {
    // Monotonic across all panes, bumped on every content change. Lets the renderer detect
    // "nothing changed" in O(1) without scanning panes or building a render key.
    private static final AtomicLong RENDER_TICK = new AtomicLong();

    public static long renderTick() {
        return RENDER_TICK.get();
    }

    private final Terminal terminal;
    private final MouseEncoder mouseEncoder = new MouseEncoder();
    // A persistent render state (reused across frames) is what makes ghostty's per-row
    // dirty tracking meaningful: update() accumulates dirty since the last resetDirty().
    private final RenderState renderState = new RenderState();
    private RenderStateSnapshot cachedSnapshot;
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
    private long snapshotVersion = -1;

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

    /**
     * Incremental snapshot: cells are marshalled only for rows that changed since the last
     * frame (global dirty == PARTIAL), reused across calls for the same content version.
     * Snapshotting is deferred here rather than done in refresh(), so a burst of writes
     * between two frames collapses into a single snapshot.
     */
    public RenderStateSnapshot renderSnapshot() {
        return snapshot(false);
    }

    /**
     * Full snapshot with every row's cells populated. Used where the whole pane is redrawn
     * regardless of dirty state (the kitty-graphics path).
     */
    public RenderStateSnapshot renderSnapshotFull() {
        return snapshot(true);
    }

    private RenderStateSnapshot snapshot(boolean full) {
        synchronized (terminal) {
            if (full) {
                renderState.update(terminal);
                cachedSnapshot = renderState.snapshot();
                renderState.resetDirty();
                snapshotVersion = renderVersion;
            } else if (snapshotVersion != renderVersion) {
                renderState.update(terminal);
                cachedSnapshot = renderState.snapshotIncremental();
                renderState.resetDirty();
                snapshotVersion = renderVersion;
            }
            return cachedSnapshot;
        }
    }

    public String scrollbackText() {
        synchronized (terminal) {
            return terminal.text();
        }
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
        // Only mark the pane dirty; the snapshot itself is computed lazily in
        // renderSnapshot() so a burst of writes collapses into a single snapshot per frame.
        renderVersion++;
        RENDER_TICK.incrementAndGet();
    }

    @Override
    public void close() {
        if (session != null) {
            session.close();
            session = null;
        }
        mouseEncoder.close();
        renderState.close();
        terminal.close();
    }
}
